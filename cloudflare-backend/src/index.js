const DEFAULT_ORIGINS = ['https://appassets.androidplatform.net'];
const MAX_BODY_BYTES = 4096;
const HEALTH_SAMPLE_MS = 5 * 60 * 1000;

function response(body, status, headers) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers }
  });
}

function corsHeaders(request, env) {
  const origin = request.headers.get('Origin');
  const allowed = (env.ALLOWED_ORIGINS || DEFAULT_ORIGINS.join(','))
    .split(',')
    .map(value => value.trim())
    .filter(Boolean);

  if (origin && !allowed.includes(origin)) return null;

  const headers = {
    Vary: 'Origin',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Max-Age': '86400'
  };
  if (origin) headers['Access-Control-Allow-Origin'] = origin;
  return headers;
}

function authorized(request, env, secretName) {
  const expected = `Bearer ${env[secretName] || ''}`;
  const received = request.headers.get('Authorization') || '';
  if (!env[secretName] || received.length !== expected.length) return false;

  let difference = 0;
  for (let index = 0; index < expected.length; index++) {
    difference |= received.charCodeAt(index) ^ expected.charCodeAt(index);
  }
  return difference === 0;
}

function optionalNumber(value, name, minimum, maximum, integer = false) {
  if (value === undefined || value === null) return null;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < minimum || value > maximum || (integer && !Number.isInteger(value))) {
    throw new Error(`Invalid ${name}.`);
  }
  return value;
}

function validateEvent(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Expected a JSON event.');
  if (!['health_update', 'run_update', 'run_completed'].includes(input.event)) throw new Error('Invalid event type.');
  if (typeof input.recording !== 'boolean') throw new Error('Invalid recording status.');

  const runEvent = input.event !== 'health_update';
  const runId = input.runId === undefined || input.runId === null ? null : String(input.runId);
  if (runEvent && (!/^\d{1,10}$/.test(runId || '') || Number(runId) < 1 || Number(runId) > 4102444800)) throw new Error('Invalid run ID.');

  const startedAt = optionalNumber(input.startedAt, 'start time', 1, 4102444800, true);
  const distanceMeters = optionalNumber(input.distanceMeters, 'distance', 0, 1000000);
  const elapsedTimeMs = optionalNumber(input.elapsedTimeMs, 'elapsed time', 0, 604800000, true);
  const currentSpeedMps = optionalNumber(input.currentSpeedMps, 'speed', 0, 30);
  const heartRateBpm = optionalNumber(input.heartRateBpm, 'heart rate', 25, 240, true);
  const temperature = optionalNumber(input.temperature, 'temperature', -20, 80);
  if (input.fitSaved !== undefined && input.fitSaved !== null && typeof input.fitSaved !== 'boolean') throw new Error('Invalid FIT save status.');

  return {
    event: input.event,
    runId,
    startedAt: startedAt || (runId ? Number(runId) : null),
    recording: input.recording,
    distanceMeters: distanceMeters ?? 0,
    elapsedTimeMs: elapsedTimeMs ?? 0,
    currentSpeedMps,
    heartRateBpm,
    temperature,
    fitSaved: input.fitSaved ?? null
  };
}

async function saveEvent(input, env, now = Date.now()) {
  const bucket = Math.floor(now / HEALTH_SAMPLE_MS);
  let healthSampleStored = false;

  if (input.heartRateBpm !== null || input.temperature !== null) {
    const result = await env.DB.prepare(
      'INSERT OR IGNORE INTO health_samples (sample_bucket, sample_time, heart_rate_bpm, temperature_c) VALUES (?, ?, ?, ?)'
    ).bind(bucket, now, input.heartRateBpm, input.temperature).run();
    healthSampleStored = result.meta?.changes > 0;
  }

  if (input.event !== 'health_update') {
    const completedAt = input.event === 'run_completed' ? now : null;
    await env.DB.prepare(`
      INSERT INTO activities (
        run_id, started_at, updated_at, completed_at, recording, distance_meters,
        elapsed_time_ms, current_speed_mps, heart_rate_bpm, temperature_c, fit_saved, source_event
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(run_id) DO UPDATE SET
        updated_at = excluded.updated_at,
        completed_at = COALESCE(excluded.completed_at, activities.completed_at),
        recording = excluded.recording,
        distance_meters = excluded.distance_meters,
        elapsed_time_ms = excluded.elapsed_time_ms,
        current_speed_mps = excluded.current_speed_mps,
        heart_rate_bpm = excluded.heart_rate_bpm,
        temperature_c = excluded.temperature_c,
        fit_saved = COALESCE(excluded.fit_saved, activities.fit_saved),
        source_event = excluded.source_event
    `).bind(
      input.runId,
      input.startedAt || now,
      now,
      completedAt,
      Number(input.recording),
      input.distanceMeters,
      input.elapsedTimeMs,
      input.currentSpeedMps,
      input.heartRateBpm,
      input.temperature,
      input.fitSaved === null ? null : Number(input.fitSaved),
      input.event
    ).run();
  }

  return { ok: true, healthSampleStored };
}

export default {
  async fetch(request, env) {
    const headers = corsHeaders(request, env);
    if (!headers) return response({ error: 'Origin not allowed.' }, 403, { Vary: 'Origin' });
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers });

    const url = new URL(request.url);
    if (!['/api/events', '/api/activities', '/api/health'].includes(url.pathname)) {
      return response({ error: 'Not found.' }, 404, headers);
    }
    const secretName = url.pathname === '/api/events' ? 'WATCH_API_TOKEN' : 'PHONE_API_TOKEN';
    if (!authorized(request, env, secretName)) return response({ error: 'Unauthorized.' }, 401, headers);
    if (!env.DB) return response({ error: 'Database is not configured.' }, 503, headers);

    try {
      if (url.pathname === '/api/events') {
        if (request.method !== 'POST') return response({ error: 'Method not allowed.' }, 405, headers);
        const declaredLength = Number(request.headers.get('Content-Length') || 0);
        if (declaredLength > MAX_BODY_BYTES) return response({ error: 'Payload too large.' }, 413, headers);
        const body = await request.text();
        if (new TextEncoder().encode(body).byteLength > MAX_BODY_BYTES) return response({ error: 'Payload too large.' }, 413, headers);
        const saved = await saveEvent(validateEvent(JSON.parse(body)), env);
        return response(saved, 200, headers);
      }

      if (request.method !== 'GET') return response({ error: 'Method not allowed.' }, 405, headers);
      if (url.pathname === '/api/activities') {
        const result = await env.DB.prepare(`
          SELECT run_id, started_at, updated_at, completed_at, recording, distance_meters,
            elapsed_time_ms, current_speed_mps, heart_rate_bpm, temperature_c, fit_saved
          FROM activities ORDER BY started_at DESC LIMIT 100
        `).all();
        return response({ activities: result.results || [] }, 200, headers);
      }

      const result = await env.DB.prepare(`
        SELECT sample_time, heart_rate_bpm, temperature_c
        FROM health_samples WHERE sample_time >= ? ORDER BY sample_bucket DESC LIMIT 288
      `).bind(Date.now() - 24 * 60 * 60 * 1000).all();
      return response({ samples: result.results || [] }, 200, headers);
    } catch (error) {
      if (error instanceof SyntaxError || error instanceof Error) {
        const message = error.message.startsWith('Invalid ') || error.message.startsWith('Expected a JSON')
          ? error.message
          : error instanceof SyntaxError ? 'Invalid JSON.' : 'Request could not be processed.';
        const status = message.startsWith('Invalid ') || message.startsWith('Expected a JSON') || message === 'Invalid JSON.' ? 400 : 500;
        return response({ error: message }, status, headers);
      }
      return response({ error: 'Request could not be processed.' }, 500, headers);
    }
  }
};

export { validateEvent };