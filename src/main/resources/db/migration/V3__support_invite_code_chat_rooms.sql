ALTER TABLE users ALTER COLUMN nickname DROP NOT NULL;

ALTER TABLE chat_rooms ALTER COLUMN user_b_id DROP NOT NULL;
ALTER TABLE chat_rooms ADD COLUMN invite_code VARCHAR;
ALTER TABLE chat_rooms ADD CONSTRAINT uq_chat_rooms_invite_code UNIQUE (invite_code);
