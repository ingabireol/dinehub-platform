-- Demo menu for local, dev and test. Not applied in production.

INSERT INTO categories (id, name, description, display_order) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'Starters',  'Small plates to begin',      1),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'Mains',     'Hearty main courses',        2),
    ('aaaaaaaa-0000-0000-0000-000000000003', 'Desserts',  'Something sweet',            3),
    ('aaaaaaaa-0000-0000-0000-000000000004', 'Drinks',    'Soft drinks, juices and tea', 4)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_items
    (id, category_id, name, description, price, available, preparation_minutes, image_url) VALUES
    ('bbbbbbbb-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001',
     'Sambusa (3 pieces)', 'Crisp pastry with spiced lentils', 3.50, TRUE, 10, NULL),
    ('bbbbbbbb-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001',
     'Soup of the Day', 'Ask your server — changes daily', 4.00, TRUE, 8, NULL),
    ('bbbbbbbb-0000-0000-0000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000002',
     'Grilled Tilapia', 'Whole fish, rice and greens', 12.50, TRUE, 25, NULL),
    ('bbbbbbbb-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000002',
     'Beef Brochette', 'Skewered beef with chips and salad', 11.00, TRUE, 20, NULL),
    ('bbbbbbbb-0000-0000-0000-000000000005', 'aaaaaaaa-0000-0000-0000-000000000002',
     'Vegetable Curry', 'Seasonal vegetables, coconut, rice', 9.50, TRUE, 18, NULL),
    ('bbbbbbbb-0000-0000-0000-000000000006', 'aaaaaaaa-0000-0000-0000-000000000003',
     'Fruit Platter', 'Whatever is ripe today', 5.00, TRUE, 5, NULL),
    -- Deliberately unavailable, so the "sold out" path has something to show.
    ('bbbbbbbb-0000-0000-0000-000000000007', 'aaaaaaaa-0000-0000-0000-000000000003',
     'Chocolate Tart', 'Rich, dark, small', 6.00, FALSE, 5, NULL),
    ('bbbbbbbb-0000-0000-0000-000000000008', 'aaaaaaaa-0000-0000-0000-000000000004',
     'Fresh Juice', 'Passion fruit, mango or mixed', 3.00, TRUE, 3, NULL),
    ('bbbbbbbb-0000-0000-0000-000000000009', 'aaaaaaaa-0000-0000-0000-000000000004',
     'Rwandan Tea', 'With or without milk', 2.00, TRUE, 4, NULL)
ON CONFLICT (id) DO NOTHING;
