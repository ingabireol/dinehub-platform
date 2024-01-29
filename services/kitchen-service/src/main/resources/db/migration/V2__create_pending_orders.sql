-- What an order contains, remembered from order.placed so that the ticket
-- created at payment.completed is legible.
--
-- A NEW migration rather than an edit to V1. Flyway records a checksum for every
-- applied migration and refuses to start when one changes — which is correct:
-- editing an applied migration means the schema in an environment that already
-- ran it differs from the schema a fresh environment would get, and nothing
-- would tell you.
--
-- The kitchen needs two facts from two events: what to cook (known at
-- order.placed) and whether to cook it (known at payment.completed). Calling
-- order-service back would put a synchronous dependency on the dinner-rush path
-- and blank the board whenever order-service restarts.

CREATE TABLE pending_orders (
    order_id      UUID          PRIMARY KEY,
    customer_id   UUID          NOT NULL,
    items_summary VARCHAR(1000) NOT NULL,
    item_count    INTEGER       NOT NULL DEFAULT 0,
    recorded_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Drives the cleanup of orders that were never paid. Without it the table grows
-- forever: every declined payment leaves a row no ticket will ever consume.
CREATE INDEX pending_orders_recorded_at_idx ON pending_orders (recorded_at);
