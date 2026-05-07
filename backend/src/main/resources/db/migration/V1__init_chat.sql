-- MySQL 8.0+
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- =====================================
-- 1) USERS
-- =====================================
CREATE TABLE users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NULL,
    avatar_url VARCHAR(500) NULL,

    status ENUM('ACTIVE','INACTIVE','BANNED')
        NOT NULL DEFAULT 'ACTIVE',

    created_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    updated_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_users_username (username),
    KEY idx_users_status (status)

) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;

-- =====================================
-- 2) CONVERSATIONS
-- =====================================
CREATE TABLE conversations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    type ENUM('DIRECT','GROUP')
        NOT NULL,

    title VARCHAR(255) NULL,

    created_by BIGINT UNSIGNED NOT NULL,

    -- add FK later
    last_message_id BIGINT UNSIGNED NULL,

    last_message_at DATETIME(3) NULL,

    created_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    updated_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    KEY idx_conversations_type (type),
    KEY idx_conversations_last_message_at (last_message_at),
    KEY idx_conversations_created_by (created_by),

    CONSTRAINT fk_conversations_created_by
        FOREIGN KEY (created_by)
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT

) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;

-- =====================================
-- 3) CONVERSATION MEMBERS
-- =====================================
CREATE TABLE conversation_members (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    conversation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,

    role ENUM('OWNER','ADMIN','MEMBER')
        NOT NULL DEFAULT 'MEMBER',

    muted_until DATETIME(3) NULL,

    joined_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    left_at DATETIME(3) NULL,

    is_active TINYINT(1)
        NOT NULL DEFAULT 1,

    PRIMARY KEY (id),

    UNIQUE KEY uk_conv_member_unique (conversation_id, user_id),

    KEY idx_conv_member_user (user_id),
    KEY idx_conv_member_active (conversation_id, is_active),

    CONSTRAINT fk_conv_member_conversation
        FOREIGN KEY (conversation_id)
        REFERENCES conversations(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT fk_conv_member_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE

) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;

-- =====================================
-- 4) MESSAGES
-- =====================================
CREATE TABLE messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    conversation_id BIGINT UNSIGNED NOT NULL,
    sender_id BIGINT UNSIGNED NOT NULL,

    seq BIGINT UNSIGNED NOT NULL,

    client_msg_id VARCHAR(64) NOT NULL,

    content_type ENUM('TEXT','IMAGE','FILE','SYSTEM')
        NOT NULL DEFAULT 'TEXT',

    content_json JSON NOT NULL,

    status ENUM('SENT','DELIVERED','READ','DELETED')
        NOT NULL DEFAULT 'SENT',

    created_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    edited_at DATETIME(3) NULL,
    deleted_at DATETIME(3) NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_msg_conversation_seq (conversation_id, seq),

    UNIQUE KEY uk_msg_sender_client_msg_id
        (sender_id, client_msg_id),

    KEY idx_msg_conversation_created
        (conversation_id, created_at),

    KEY idx_msg_sender_created
        (sender_id, created_at),

    CONSTRAINT fk_msg_conversation
        FOREIGN KEY (conversation_id)
        REFERENCES conversations(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT fk_msg_sender
        FOREIGN KEY (sender_id)
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE RESTRICT

) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;

-- =====================================
-- ADD last_message FK AFTER messages
-- =====================================
ALTER TABLE conversations
ADD CONSTRAINT fk_conversations_last_message
FOREIGN KEY (last_message_id)
REFERENCES messages(id)
ON UPDATE CASCADE
ON DELETE SET NULL;

-- =====================================
-- 5) MESSAGE RECEIPTS
-- =====================================
CREATE TABLE message_receipts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    message_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,

    delivered_at DATETIME(3) NULL,
    read_at DATETIME(3) NULL,

    created_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    updated_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_receipt_message_user
        (message_id, user_id),

    KEY idx_receipt_user_read
        (user_id, read_at),

    CONSTRAINT fk_receipt_message
        FOREIGN KEY (message_id)
        REFERENCES messages(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT fk_receipt_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE

) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;

-- =====================================
-- 6) CONVERSATION READ STATE
-- =====================================
CREATE TABLE conversation_read_state (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    conversation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,

    last_read_seq BIGINT UNSIGNED
        NOT NULL DEFAULT 0,

    last_read_message_id BIGINT UNSIGNED NULL,

    updated_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_read_state_conv_user
        (conversation_id, user_id),

    KEY idx_read_state_user (user_id),

    CONSTRAINT fk_read_state_conversation
        FOREIGN KEY (conversation_id)
        REFERENCES conversations(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT fk_read_state_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT fk_read_state_last_message
        FOREIGN KEY (last_read_message_id)
        REFERENCES messages(id)
        ON UPDATE CASCADE
        ON DELETE SET NULL

) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;

-- =====================================
-- 7) DEVICES
-- =====================================
CREATE TABLE devices (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    user_id BIGINT UNSIGNED NOT NULL,

    device_id VARCHAR(128) NOT NULL,

    platform ENUM('WEB','ANDROID','IOS','DESKTOP')
        NOT NULL DEFAULT 'WEB',

    push_token VARCHAR(255) NULL,

    app_version VARCHAR(50) NULL,

    is_active TINYINT(1)
        NOT NULL DEFAULT 1,

    last_active_at DATETIME(3) NULL,

    created_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    updated_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_device_user_device
        (user_id, device_id),

    KEY idx_device_push_token (push_token),

    CONSTRAINT fk_device_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE

) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;

-- =====================================
-- 8) USER PRESENCE
-- =====================================
CREATE TABLE user_presence (
    user_id BIGINT UNSIGNED NOT NULL,

    is_online TINYINT(1)
        NOT NULL DEFAULT 0,

    last_seen_at DATETIME(3) NULL,

    updated_at DATETIME(3)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (user_id),

    CONSTRAINT fk_presence_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON UPDATE CASCADE
        ON DELETE CASCADE

) ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci;