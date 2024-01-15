-- Menu schema.

CREATE TABLE categories (
    id            UUID         PRIMARY KEY,
    name          VARCHAR(80)  NOT NULL,
    description   VARCHAR(400),
    display_order INTEGER      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX categories_name_unique_idx ON categories (LOWER(name));
CREATE INDEX categories_display_order_idx ON categories (display_order);

CREATE TABLE menu_items (
    id                  UUID           PRIMARY KEY,
    category_id         UUID           NOT NULL,
    name                VARCHAR(140)   NOT NULL,
    description         VARCHAR(600),
    -- NUMERIC, never a floating-point type. A double cannot represent 0.10
    -- exactly and the error compounds across an order.
    price               NUMERIC(10, 2) NOT NULL,
    available           BOOLEAN        NOT NULL DEFAULT TRUE,
    preparation_minutes INTEGER        NOT NULL DEFAULT 15,
    image_url           VARCHAR(500),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version             BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT menu_items_category_fk FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT menu_items_price_positive CHECK (price > 0),
    CONSTRAINT menu_items_prep_sane CHECK (preparation_minutes BETWEEN 1 AND 240)
);

-- ON DELETE RESTRICT, not CASCADE: deleting a category should fail loudly
-- rather than silently removing items that may be on live orders.

CREATE INDEX menu_items_category_idx ON menu_items (category_id);
-- Partial index: the customer-facing menu only ever queries available items,
-- and this keeps that query off the unavailable rows entirely.
CREATE INDEX menu_items_available_idx ON menu_items (available) WHERE available = TRUE;

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
