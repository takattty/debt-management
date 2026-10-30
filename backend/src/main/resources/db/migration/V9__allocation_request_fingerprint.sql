-- Existing internal ledger facts remain valid. Public commands always supply a canonical fingerprint.
ALTER TABLE allocation_operation ADD COLUMN request_fingerprint varchar(64)
    CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');
