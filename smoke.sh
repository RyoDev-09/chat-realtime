#!/usr/bin/env sh
set -eu

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_A="${SMOKE_USER_A:-smoke_a}"
USER_B="${SMOKE_USER_B:-smoke_b}"
PASS="${SMOKE_PASS:-123456}"

json_field() {
  # usage: json_field "$json" "userId"
  echo "$1" | sed -n "s/.*\"$2\":\([0-9][0-9]*\).*/\1/p"
}

echo "[1/7] login users"
A_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$USER_A\",\"password\":\"$PASS\"}")
B_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$USER_B\",\"password\":\"$PASS\"}")
AID=$(json_field "$A_LOGIN" "userId")
BID=$(json_field "$B_LOGIN" "userId")
if [ -z "${AID:-}" ]; then
  curl -s -X POST "$BASE_URL/auth/register" -H 'Content-Type: application/json' -d "{\"username\":\"$USER_A\",\"displayName\":\"Smoke A\",\"password\":\"$PASS\"}" >/dev/null
  A_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$USER_A\",\"password\":\"$PASS\"}")
fi
if [ -z "${BID:-}" ]; then
  curl -s -X POST "$BASE_URL/auth/register" -H 'Content-Type: application/json' -d "{\"username\":\"$USER_B\",\"displayName\":\"Smoke B\",\"password\":\"$PASS\"}" >/dev/null
  B_LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$USER_B\",\"password\":\"$PASS\"}")
fi
AID=$(json_field "$A_LOGIN" "userId")
BID=$(json_field "$B_LOGIN" "userId")
[ -n "$AID" ] && [ -n "$BID" ] || { echo "❌ login/register failed"; echo "$A_LOGIN"; echo "$B_LOGIN"; exit 1; }

echo "[2/7] create direct both directions (idempotent check)"
A_TOKEN=$(echo "$A_LOGIN" | sed -n "s/.*\"token\":\"\([^\"]*\\)\".*/\1/p")
B_TOKEN=$(echo "$B_LOGIN" | sed -n "s/.*\"token\":\"\([^\"]*\\)\".*/\1/p")
C1=$(curl -s -X POST "$BASE_URL/conversations" -H 'Content-Type: application/json' -H "Authorization: Bearer $A_TOKEN" -d "{\"creatorId\":$AID,\"type\":\"DIRECT\",\"title\":null,\"memberIds\":[$BID]}")
C2=$(curl -s -X POST "$BASE_URL/conversations" -H 'Content-Type: application/json' -H "Authorization: Bearer $B_TOKEN" -d "{\"creatorId\":$BID,\"type\":\"DIRECT\",\"title\":null,\"memberIds\":[$AID]}")
CID1=$(json_field "$C1" "id")
CID2=$(json_field "$C2" "id")
[ "$CID1" = "$CID2" ] || { echo "❌ direct idempotent failed: $CID1 != $CID2"; exit 1; }
CID="$CID1"

echo "[3/7] send message A -> DIRECT"
STAMP=$(date +%s)
SEND=$(curl -s -X POST "$BASE_URL/conversations/$CID/messages" -H 'Content-Type: application/json' -H "Authorization: Bearer $A_TOKEN" -d "{\"senderId\":$AID,\"text\":\"smoke-$STAMP\",\"clientMsgId\":\"smoke-$STAMP\"}")
echo "$SEND" | grep -q '"success":true' || { echo "❌ send failed"; exit 1; }

echo "[4/7] verify unread > 0 for B"
BL1=$(curl -s -H "Authorization: Bearer $B_TOKEN" "$BASE_URL/conversations?userId=$BID")
UNREAD1=$(echo "$BL1" | sed -n "s/.*\"id\":$CID,\"type\":\"DIRECT\",\"title\":null,\"lastMessageAt\":\"[^\"]*\",\"unreadCount\":\([0-9][0-9]*\).*/\1/p")
[ -n "$UNREAD1" ] || UNREAD1=$(echo "$BL1" | sed -n 's/.*"unreadCount":\([0-9][0-9]*\).*/\1/p' | head -n1)
[ "${UNREAD1:-0}" -gt 0 ] || { echo "❌ unread did not increase"; echo "$BL1"; exit 1; }

echo "[5/7] mark-read by B"
MR=$(curl -s -X POST "$BASE_URL/conversations/$CID/messages/mark-read" -H 'Content-Type: application/json' -H "Authorization: Bearer $B_TOKEN" -d "{\"userId\":$BID}")
echo "$MR" | grep -q '"success":true' || { echo "❌ mark-read failed"; echo "$MR"; exit 1; }

echo "[6/7] verify unread = 0 for B"
BL2=$(curl -s -H "Authorization: Bearer $B_TOKEN" "$BASE_URL/conversations?userId=$BID")
UNREAD2=$(echo "$BL2" | sed -n "s/.*\"id\":$CID,\"type\":\"DIRECT\",\"title\":null,\"lastMessageAt\":\"[^\"]*\",\"unreadCount\":\([0-9][0-9]*\).*/\1/p")
[ -n "$UNREAD2" ] || UNREAD2=$(echo "$BL2" | sed -n 's/.*"unreadCount":\([0-9][0-9]*\).*/\1/p' | head -n1)
[ "${UNREAD2:-999}" -eq 0 ] || { echo "❌ unread not cleared"; echo "$BL2"; exit 1; }

echo "[7/7] direct peer endpoint check"
DP=$(curl -s -H "Authorization: Bearer $A_TOKEN" "$BASE_URL/conversations/$CID/direct-peer?userId=$AID")
echo "$DP" | grep -q '"success":true' || { echo "❌ direct-peer failed"; echo "$DP"; exit 1; }

echo "✅ SMOKE PASS: core MVP chat flow healthy"
