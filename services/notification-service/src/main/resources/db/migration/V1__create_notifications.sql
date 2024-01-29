-- In-app notifications.

CREATE TABLE notifications (
    id               UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL,
    type             VARCHAR(24)  NOT NULL,
    title            VARCHAR(140) NOT NULL,
    message          VARCHAR(600) NOT NULL,
    related_order_id UUID,
    read_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT notifications_type_check CHECK (type IN (
        'WELCOME', 'ORDER_PLACED', 'ORDER_PAID', 'ORDER_PREPARING',
        'ORDER_READY', 'ORDER_DELIVERED', 'ORDER_CANCELLED', 'PAYMENT_FAILED'))
);

-- The list query: one user's notifications, newest first.
CREATE INDEX notifications_user_created_idx ON notifications (user_id, created_at DESC);

-- The unread badge polls this constantly, and a partial index keeps it off the
-- read rows entirely — which is the overwhelming majority after a week.
CREATE INDEX notifications_unread_idx ON notifications (user_id)
    WHERE read_at IS NULL;

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);
