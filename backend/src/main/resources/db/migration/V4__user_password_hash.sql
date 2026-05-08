ALTER TABLE users
    ADD COLUMN password_hash VARCHAR(255) NULL AFTER display_name;
