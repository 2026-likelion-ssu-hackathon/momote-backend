ALTER TABLE users ADD COLUMN gender VARCHAR;
ALTER TABLE users ADD CONSTRAINT ck_users_gender CHECK (gender IN ('MALE', 'FEMALE'));

ALTER TABLE chat_room_join_requests ADD COLUMN gender VARCHAR;
ALTER TABLE chat_room_join_requests ADD CONSTRAINT ck_join_requests_gender CHECK (gender IN ('MALE', 'FEMALE'));
