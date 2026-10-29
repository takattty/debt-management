CREATE TABLE invitation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    email_canonical varchar(254) NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'ACCOUNTANT')),
    token_hash char(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_by_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    accepted_user_id uuid,
    CHECK (email_canonical = lower(btrim(email_canonical))),
    CHECK (email_canonical ~ '^[!-~]+@[!-~]+$'),
    CHECK (expires_at > created_at),
    CHECK ((accepted_at IS NULL) = (accepted_user_id IS NULL)),
    CHECK (accepted_at IS NULL OR accepted_at >= created_at),
    CHECK (accepted_at IS NULL OR accepted_at < expires_at),
    FOREIGN KEY (tenant_id, created_by_user_id) REFERENCES app_user(tenant_id, id),
    FOREIGN KEY (tenant_id, accepted_user_id) REFERENCES app_user(tenant_id, id)
);
CREATE INDEX invitation_tenant_email ON invitation(tenant_id, email_canonical);
