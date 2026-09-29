-- 掌心窗 Cloudflare D1 schema
CREATE TABLE IF NOT EXISTS device_state (
  device_id TEXT PRIMARY KEY,
  state_json TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS commands (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  command_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at TEXT NOT NULL,
  dispatched_at TEXT,
  completed_at TEXT,
  result TEXT,
  activity_event_id TEXT
);
CREATE INDEX IF NOT EXISTS idx_commands_pending ON commands(device_id, status, created_at);

CREATE TABLE IF NOT EXISTS activity_events (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  source TEXT,
  type TEXT,
  title TEXT,
  subtitle TEXT,
  app_name TEXT,
  package_name TEXT,
  action TEXT,
  status TEXT,
  metadata_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_activity_device_created ON activity_events(device_id, created_at);

CREATE TABLE IF NOT EXISTS companion_state (
  key TEXT PRIMARY KEY,
  value_json TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS unlock_requests (
  id TEXT PRIMARY KEY,
  request_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);
