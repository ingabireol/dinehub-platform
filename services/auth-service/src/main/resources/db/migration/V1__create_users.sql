-- Users and the processed-event ledger.
--
-- Flyway owns this schema. Hibernate runs with ddl-auto=validate, so a mismatch
-- between an entity and a migration fails at startup rather than silently
-- altering a production table.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(160) NOT NULL,
    password_hash VARCHAR(72)  NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'KITCHEN', 'ADMIN'))
);

-- Case-insensitive uniqueness. Without LOWER(), 'Chef@dinehub.local' and
-- 'chef@dinehub.local' are two accounts, which is a login support ticket
-- waiting to happen.
CREATE UNIQUE INDEX users_email_unique_idx ON users (LOWER(email));

CREATE INDEX users_role_idx ON users (role);

-- The idempotency ledger. Every service that consumes events has one.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

COMMENT ON TABLE processed_events IS
    'Event ids already handled by this service. The primary key is what makes '
    'consumers idempotent under at-least-once delivery.';
