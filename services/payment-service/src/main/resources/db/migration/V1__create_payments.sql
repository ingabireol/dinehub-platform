-- Payment records.

CREATE TABLE payments (
    id             UUID           PRIMARY KEY,
    -- Unique, and this constraint is the thing that actually prevents a double
    -- charge. An application-level "have we paid this already?" check has a
    -- race between the check and the insert; the database does not.
    order_id       UUID           NOT NULL UNIQUE,
    customer_id    UUID           NOT NULL,
    amount         NUMERIC(10, 2) NOT NULL,
    status         VARCHAR(16)    NOT NULL,
    reference      VARCHAR(32)    NOT NULL UNIQUE,
    failure_reason VARCHAR(200),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT payments_status_check CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT payments_amount_positive CHECK (amount > 0),
    -- A failure must say why; a success must not claim one.
    CONSTRAINT payments_failure_reason_consistent CHECK (
        (status = 'FAILED' AND failure_reason IS NOT NULL) OR
        (status = 'COMPLETED' AND failure_reason IS NULL))
);

CREATE INDEX payments_customer_created_idx ON payments (customer_id, created_at DESC);
CREATE INDEX payments_status_idx ON payments (status);

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);
