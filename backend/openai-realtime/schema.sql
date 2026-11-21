CREATE TABLE IF NOT EXISTS pairing_codes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  code_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_pairing_codes_expiry ON pairing_codes(expires_at, consumed_at);

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  device_name TEXT NOT NULL,
  refresh_token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  refresh_expires_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_refresh_hash ON devices(refresh_token_hash);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id),
  created_at INTEGER NOT NULL,
  rotated_at INTEGER,
  replaced_by_hash TEXT,
  status TEXT NOT NULL CHECK(status IN ('active', 'rotated', 'revoked'))
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_device ON refresh_tokens(device_id, status);

CREATE TABLE IF NOT EXISTS rate_limits (
  bucket_key TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  hit_count INTEGER NOT NULL
);
