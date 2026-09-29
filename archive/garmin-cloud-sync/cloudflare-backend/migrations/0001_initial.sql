CREATE TABLE activities (
  run_id TEXT PRIMARY KEY,
  started_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  completed_at INTEGER,
  recording INTEGER NOT NULL,
  distance_meters REAL NOT NULL DEFAULT 0,
  elapsed_time_ms INTEGER NOT NULL DEFAULT 0,
  current_speed_mps REAL,
  heart_rate_bpm INTEGER,
  temperature_c REAL,
  fit_saved INTEGER,
  source_event TEXT NOT NULL
);

CREATE INDEX activities_started_at_idx ON activities (started_at DESC);

CREATE TABLE health_samples (
  sample_bucket INTEGER PRIMARY KEY,
  sample_time INTEGER NOT NULL,
  heart_rate_bpm INTEGER,
  temperature_c REAL
);

CREATE INDEX health_samples_time_idx ON health_samples (sample_time DESC);