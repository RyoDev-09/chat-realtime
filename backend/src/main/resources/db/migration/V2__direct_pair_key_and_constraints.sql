ALTER TABLE conversations
ADD COLUMN direct_pair_key VARCHAR(64) NULL AFTER title;

CREATE UNIQUE INDEX uk_conversations_direct_pair_key
ON conversations (direct_pair_key);
