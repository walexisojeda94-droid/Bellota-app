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
  previous_refresh_token_hash TEXT,
  previous_refresh_valid_until INTEGER,
  created_at INTEGER NOT NULL,
  refresh_expires_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_refresh_hash ON devices(refresh_token_hash);
