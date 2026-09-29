import assert from 'node:assert/strict';
import test from 'node:test';
import worker from '../src/index.js';

const watchToken = 'local-watch-token';
const phoneToken = 'local-phone-token';
const origin = 'https://appassets.androidplatform.net';

function createDatabase() {
  const activities = new Map();
  const healthSamples = new Map();
  return {
    activities,
    healthSamples,
    prepare(sql) {
      const readRows = async values => {
        if (sql.includes('FROM activities')) return { results: [...activities.values()] };
        if (sql.includes('FROM health_samples')) return { results: [...healthSamples.values()].filter(sample => sample.sample_time >= (values[0] || 0)) };
        throw new Error('Unexpected SQL in test: ' + sql);
      };
      return {
        bind(...values) {
          return {
            async run() {
              if (sql.includes('INSERT OR IGNORE INTO health_samples')) {
                const [bucket, sampleTime, heartRate, temperature] = values;
                const inserted = !healthSamples.has(bucket);
                if (inserted) healthSamples.set(bucket, { sample_time: sampleTime, heart_rate_bpm: heartRate, temperature_c: temperature });
                return { meta: { changes: Number(inserted) } };
              }
              if (sql.includes('INSERT INTO activities')) {
                const [runId, startedAt, updatedAt, completedAt, recording, distance, elapsed, speed, heartRate, temperature, fitSaved] = values;
                const old = activities.get(runId) || {};
                activities.set(runId, {
                  run_id: runId,
                  started_at: old.started_at || startedAt,
                  updated_at: updatedAt,
                  completed_at: completedAt ?? old.completed_at ?? null,
                  recording,
                  distance_meters: distance,
                  elapsed_time_ms: elapsed,
                  current_speed_mps: speed,
                  heart_rate_bpm: heartRate,
                  temperature_c: temperature,
                  fit_saved: fitSaved ?? old.fit_saved ?? null
                });
                return { meta: { changes: 1 } };
              }
              throw new Error('Unexpected SQL in test: ' + sql);
            },
            async all() {
              return readRows(values);
            }
          };
        },
        async all() { return readRows([]); }
      };
    }
  };
}

function createEnv() {
  return { WATCH_API_TOKEN: watchToken, PHONE_API_TOKEN: phoneToken, ALLOWED_ORIGINS: origin, DB: createDatabase() };
}

function request(path, { method = 'GET', body, auth = path === '/api/events' ? watchToken : phoneToken, requestOrigin = origin } = {}) {
  const headers = new Headers();
  if (auth) headers.set('Authorization', `Bearer ${auth}`);
  if (requestOrigin) headers.set('Origin', requestOrigin);
  if (body !== undefined) headers.set('Content-Type', 'application/json');
  return new Request(`https://worker.test${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}

const liveUpdate = {
  event: 'run_update',
  runId: 1790000000,
  startedAt: 1790000000,
  recording: true,
  distanceMeters: 1200,
  elapsedTimeMs: 420000,
  currentSpeedMps: 2.8,
  heartRateBpm: 142,
  temperature: 32.5,
  accelerometer: [0.1, 0.2, 0.3]
};

test('rejects requests without the configured bearer token', async () => {
  const result = await worker.fetch(request('/api/activities', { auth: 'wrong' }), createEnv());
  assert.equal(result.status, 401);
});

test('keeps watch write and phone read credentials separate', async () => {
  const env = createEnv();
  const watchRead = await worker.fetch(request('/api/activities', { auth: watchToken }), env);
  const phoneWrite = await worker.fetch(request('/api/events', { method: 'POST', auth: phoneToken, body: liveUpdate }), env);
  assert.equal(watchRead.status, 401);
  assert.equal(phoneWrite.status, 401);
});

test('answers allowed browser preflight without touching D1', async () => {
  const result = await worker.fetch(request('/api/events', { method: 'OPTIONS' }), createEnv());
  assert.equal(result.status, 204);
  assert.equal(result.headers.get('Access-Control-Allow-Origin'), origin);
});

test('blocks browser origins not explicitly allowed', async () => {
  const result = await worker.fetch(request('/api/health', { requestOrigin: 'https://other.example' }), createEnv());
  assert.equal(result.status, 403);
});

test('coalesces health writes into one five-minute sample and omits accelerometer data', async () => {
  const env = createEnv();
  const first = await worker.fetch(request('/api/events', { method: 'POST', body: { ...liveUpdate, event: 'health_update', runId: null, startedAt: null } }), env);
  const second = await worker.fetch(request('/api/events', { method: 'POST', body: { ...liveUpdate, event: 'health_update', runId: null, startedAt: null } }), env);
  assert.equal(first.status, 200);
  assert.equal((await first.json()).healthSampleStored, true);
  assert.equal((await second.json()).healthSampleStored, false);
  assert.equal(env.DB.healthSamples.size, 1);
  assert.equal(JSON.stringify([...env.DB.healthSamples.values()]).includes('accelerometer'), false);
});

test('returns only health readings from the last 24 hours', async () => {
  const env = createEnv();
  const now = Date.now();
  env.DB.healthSamples.set(1, { sample_time: now - 25 * 60 * 60 * 1000, heart_rate_bpm: 65, temperature_c: 32 });
  env.DB.healthSamples.set(2, { sample_time: now - 60 * 60 * 1000, heart_rate_bpm: 72, temperature_c: 33 });

  const result = await worker.fetch(request('/api/health'), env);
  const data = await result.json();
  assert.equal(data.samples.length, 1);
  assert.equal(data.samples[0].heart_rate_bpm, 72);
});

test('updates one activity through completion and returns it to the app', async () => {
  const env = createEnv();
  await worker.fetch(request('/api/events', { method: 'POST', body: liveUpdate }), env);
  const completed = await worker.fetch(request('/api/events', {
    method: 'POST',
    body: { ...liveUpdate, event: 'run_completed', recording: false, distanceMeters: 2500, elapsedTimeMs: 900000, fitSaved: true }
  }), env);
  assert.equal(completed.status, 200);
  assert.equal(env.DB.activities.size, 1);

  const result = await worker.fetch(request('/api/activities'), env);
  const data = await result.json();
  assert.equal(data.activities.length, 1);
  assert.equal(data.activities[0].recording, 0);
  assert.equal(data.activities[0].distance_meters, 2500);
  assert.equal(data.activities[0].fit_saved, 1);
});

test('rejects invalid sensor values and malformed JSON', async () => {
  const env = createEnv();
  const invalid = await worker.fetch(request('/api/events', {
    method: 'POST',
    body: { ...liveUpdate, heartRateBpm: 900 }
  }), env);
  assert.equal(invalid.status, 400);

  const malformed = new Request('https://worker.test/api/events', {
    method: 'POST',
    headers: { Authorization: `Bearer ${watchToken}`, Origin: origin, 'Content-Type': 'application/json' },
    body: '{'
  });
  assert.equal((await worker.fetch(malformed, env)).status, 400);

  const invalidRunId = await worker.fetch(request('/api/events', {
    method: 'POST',
    body: { ...liveUpdate, runId: 'not-a-timestamp' }
  }), env);
  assert.equal(invalidRunId.status, 400);
});