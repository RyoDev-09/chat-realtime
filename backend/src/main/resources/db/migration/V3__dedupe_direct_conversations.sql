-- Deduplicate legacy DIRECT conversations created before direct_pair_key enforcement.
-- Strategy:
-- 1) Build pair stats from active members (expect exactly 2 for DIRECT).
-- 2) Choose canonical conversation per pair (MIN conversation_id).
-- 3) Move dependent rows to canonical conversation.
-- 4) Remove duplicate conversations.
-- 5) Backfill direct_pair_key for all DIRECT conversations.

CREATE TEMPORARY TABLE tmp_direct_pair AS
SELECT
  c.id AS conversation_id,
  CONCAT(MIN(cm.user_id), ':', MAX(cm.user_id)) AS pair_key,
  COUNT(*) AS active_member_count
FROM conversations c
JOIN conversation_members cm ON cm.conversation_id = c.id AND cm.is_active = 1
WHERE c.type = 'DIRECT'
GROUP BY c.id;

CREATE TEMPORARY TABLE tmp_direct_canonical AS
SELECT pair_key, MIN(conversation_id) AS canonical_id
FROM tmp_direct_pair
WHERE active_member_count = 2
GROUP BY pair_key;

CREATE TEMPORARY TABLE tmp_direct_duplicate AS
SELECT p.conversation_id AS duplicate_id, c.canonical_id, p.pair_key
FROM tmp_direct_pair p
JOIN tmp_direct_canonical c ON c.pair_key = p.pair_key
WHERE p.active_member_count = 2
  AND p.conversation_id <> c.canonical_id;

-- Repoint child tables to canonical conversation.
UPDATE messages m
JOIN tmp_direct_duplicate d ON d.duplicate_id = m.conversation_id
SET m.conversation_id = d.canonical_id;

UPDATE conversation_members cm
JOIN tmp_direct_duplicate d ON d.duplicate_id = cm.conversation_id
SET cm.conversation_id = d.canonical_id;

UPDATE conversation_read_state crs
JOIN tmp_direct_duplicate d ON d.duplicate_id = crs.conversation_id
SET crs.conversation_id = d.canonical_id;

-- Keep only canonical DIRECT conversations.
DELETE c
FROM conversations c
JOIN tmp_direct_duplicate d ON d.duplicate_id = c.id;

-- Backfill pair key for valid DIRECT conversations.
UPDATE conversations c
JOIN tmp_direct_pair p ON p.conversation_id = c.id
SET c.direct_pair_key = p.pair_key
WHERE c.type = 'DIRECT'
  AND p.active_member_count = 2;

DROP TEMPORARY TABLE IF EXISTS tmp_direct_duplicate;
DROP TEMPORARY TABLE IF EXISTS tmp_direct_canonical;
DROP TEMPORARY TABLE IF EXISTS tmp_direct_pair;
