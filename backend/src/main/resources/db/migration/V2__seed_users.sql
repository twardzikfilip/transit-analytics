-- Development seed users: admin/admin (ADMIN) and guest/guest (GUEST).
-- Change or remove these before deploying anywhere real.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO app_user (username, password_hash, role_id)
SELECT 'admin', crypt('admin', gen_salt('bf', 10)), id FROM user_role WHERE name = 'ADMIN';

INSERT INTO app_user (username, password_hash, role_id)
SELECT 'guest', crypt('guest', gen_salt('bf', 10)), id FROM user_role WHERE name = 'GUEST';
