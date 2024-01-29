-- Kitchen tickets.

CREATE TABLE kitchen_tickets (
    id            UUID         PRIMARY KEY,
    -- One ticket per order. Two tickets for one order means two chefs cooking
    -- the same food, so the database enforces it rather than the application.
    order_id      UUID         NOT NULL UNIQUE,
    customer_id   UUID         NOT NULL,
    -- Denormalised deliberately: the board must render during a dinner rush
    -- without a synchronous call to order-service, and a ticket should stay
    -- legible if order-service is down.
    items_summary VARCHAR(1000) NOT NULL,
    item_count    INTEGER      NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL,
    claimed_by    UUID,
    queued_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at    TIMESTAMPTZ,
    ready_at      TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT kitchen_tickets_status_check CHECK (
        status IN ('QUEUED', 'PREPARING', 'READY', 'DELIVERED', 'CANCELLED')),
    -- The timestamps must tell a coherent story. A ticket that was ready before
    -- it was started is a bug, and it should fail at the insert rather than
    -- quietly poison the preparation-time metric.
    CONSTRAINT kitchen_tickets_times_ordered CHECK (
        (started_at IS NULL OR started_at >= queued_at) AND
        (ready_at IS NULL OR (started_at IS NOT NULL AND ready_at >= started_at))),
    CONSTRAINT kitchen_tickets_claimed_when_started CHECK (
        started_at IS NULL OR claimed_by IS NOT NULL)
);

-- The board query: tickets still in play, oldest first.
CREATE INDEX kitchen_tickets_board_idx ON kitchen_tickets (status, queued_at ASC);
CREATE INDEX kitchen_tickets_claimed_by_idx ON kitchen_tickets (claimed_by)
    WHERE claimed_by IS NOT NULL;

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);
