-- Demo users for local, dev and test.
--
-- NOT applied in production: the prod profile excludes this location. These
-- credentials are published in the README on purpose, which is exactly why they
-- must never exist in a production database.
--
-- All three passwords are 'DineHub2024!' hashed with BCrypt cost 12.

INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at) VALUES
    ('11111111-1111-1111-1111-111111111111',
     'customer@dinehub.local',
     '$2a$12$8aOoCUl8xEg3Dgq0v/YNFuPT3iHKvxYYXzlSQQ3WhFtKAzyRvjZvi',
     'Demo Customer', 'CUSTOMER', TRUE, NOW()),

    ('22222222-2222-2222-2222-222222222222',
     'chef@dinehub.local',
     '$2a$12$8aOoCUl8xEg3Dgq0v/YNFuPT3iHKvxYYXzlSQQ3WhFtKAzyRvjZvi',
     'Demo Chef', 'KITCHEN', TRUE, NOW()),

    ('33333333-3333-3333-3333-333333333333',
     'admin@dinehub.local',
     '$2a$12$8aOoCUl8xEg3Dgq0v/YNFuPT3iHKvxYYXzlSQQ3WhFtKAzyRvjZvi',
     'Demo Admin', 'ADMIN', TRUE, NOW())
ON CONFLICT (id) DO NOTHING;
