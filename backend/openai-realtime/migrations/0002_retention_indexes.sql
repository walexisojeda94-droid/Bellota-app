-- Apply once after 0001_recoverable_refresh.sql on an existing D1 database.
CREATE INDEX IF NOT EXISTS idx_devices_revoked_at ON devices(revoked_at);
CREATE INDEX IF NOT EXISTS idx_devices_refresh_expiry ON devices(refresh_expires_at);
