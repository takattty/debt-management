CREATE TABLE tenant (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL
);
CREATE TABLE customer (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    code varchar(50) NOT NULL CHECK (length(btrim(code)) > 0),
    name varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, code)
);
CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    actor varchar(100) NOT NULL,
    action varchar(100) NOT NULL,
    entity_id uuid NOT NULL,
    details jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX audit_event_tenant_time ON audit_event(tenant_id, occurred_at);
