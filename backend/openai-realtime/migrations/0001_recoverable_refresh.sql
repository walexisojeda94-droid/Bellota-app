-- Apply once to an existing Bellota D1 database before deploying the updated Worker.
ALTER TABLE devices ADD COLUMN previous_refresh_token_hash TEXT;
ALTER TABLE devices ADD COLUMN previous_refresh_valid_until INTEGER;
