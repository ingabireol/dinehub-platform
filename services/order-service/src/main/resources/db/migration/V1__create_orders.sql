-- Order schema.

CREATE TABLE orders (
    id                  UUID           PRIMARY KEY,
    customer_id         UUID           NOT NULL,
    customer_email      VARCHAR(160)   NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    total_amount        NUMERIC(10, 2) NOT NULL DEFAULT 0,
    delivery_address    VARCHAR(400),
    notes               VARCHAR(500),
    cancellation_reason VARCHAR(300),
    placed_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version             BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT orders_status_check CHECK (
        status IN ('PLACED', 'PAID', 'PREPARING', 'READY', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT orders_total_non_negative CHECK (total_amount >= 0)
);

-- The three queries that actually run: a customer's order history, the kitchen
-- queue by status, and the stuck-order sweep.
CREATE INDEX orders_customer_placed_idx ON orders (customer_id, placed_at DESC);
CREATE INDEX orders_status_placed_idx ON orders (status, placed_at ASC);

CREATE TABLE order_items (
    id           UUID           PRIMARY KEY,
    order_id     UUID           NOT NULL,
    menu_item_id UUID           NOT NULL,
    -- Snapshotted from the menu at the moment the order was placed. Deliberately
    -- duplicated rather than joined: an order must record what the customer was
    -- actually charged, and a later price change must not rewrite history.
    item_name    VARCHAR(140)   NOT NULL,
    unit_price   NUMERIC(10, 2) NOT NULL,
    quantity     INTEGER        NOT NULL,

    CONSTRAINT order_items_order_fk FOREIGN KEY (order_id)
        REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_items_price_non_negative CHECK (unit_price >= 0)
);

-- CASCADE here is correct: a line has no meaning without its order, unlike a
-- menu item, which has meaning without its category.
CREATE INDEX order_items_order_idx ON order_items (order_id);
CREATE INDEX order_items_menu_item_idx ON order_items (menu_item_id);

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);
