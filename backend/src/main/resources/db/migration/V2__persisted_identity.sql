CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    email_canonical varchar(254) NOT NULL UNIQUE,
    password_hash varchar(512) NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
    role varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'ACCOUNTANT')),
    enabled boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (email_canonical = lower(btrim(email_canonical))),
    CHECK (email_canonical ~ '^[!-~]+@[!-~]+$'),
    UNIQUE (tenant_id, id)
);
ALTER TABLE audit_event ADD COLUMN actor_user_id uuid;
ALTER TABLE audit_event ADD CONSTRAINT audit_actor_membership_fk
    FOREIGN KEY (tenant_id, actor_user_id) REFERENCES app_user(tenant_id, id);
CREATE INDEX app_user_tenant ON app_user(tenant_id);
