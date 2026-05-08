#!/usr/bin/env sh
set -eu

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_A="${SMOKE_USER_A:-smoke_a}"
USER_B="${SMOKE_USER_B:-smoke_b}"
USER_C="${SMOKE_USER_C:-smoke_c}"
PASS="${SMOKE_PASS:-123456}"

json_num() { echo "$1" | sed -n "s/.*\"$2\":\([0-9][0-9]*\).*/\1/p"; }
json_token() { echo "$1" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'; }

login_or_register() {
  user="$1"; name="$2"
  L=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$user\",\"password\":\"$PASS\"}")
  id=$(json_num "$L" "userId")
  if [ -z "${id:-}" ]; then
    curl -s -X POST "$BASE_URL/auth/register" -H 'Content-Type: application/json' -d "{\"username\":\"$user\",\"displayName\":\"$name\",\"password\":\"$PASS\"}" >/dev/null
    L=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$user\",\"password\":\"$PASS\"}")
    id=$(json_num "$L" "userId")
  fi
  token=$(json_token "$L")
  [ -n "${id:-}" ] && [ -n "${token:-}" ] || { echo "❌ cannot prepare user $user"; echo "$L"; exit 1; }
  echo "$id|$token"
}

A=$(login_or_register "$USER_A" "Smoke A")
B=$(login_or_register "$USER_B" "Smoke B")
CUSER=$(login_or_register "$USER_C" "Smoke C")
AID=$(echo "$A" | cut -d'|' -f1)
ATK=$(echo "$A" | cut -d'|' -f2)
BID=$(echo "$B" | cut -d'|' -f1)
BTK=$(echo "$B" | cut -d'|' -f2)
CID3=$(echo "$CUSER" | cut -d'|' -f1)

echo "[N1] no token must be 401"
S1=$(curl -s -o /tmp/n1.out -w '%{http_code}' "$BASE_URL/conversations?userId=$AID")
[ "$S1" = "401" ] || { echo "❌ expected 401, got $S1"; cat /tmp/n1.out; exit 1; }

echo "[N2] token A but query userId B must be 403"
S2=$(curl -s -o /tmp/n2.out -w '%{http_code}' -H "Authorization: Bearer $ATK" "$BASE_URL/conversations?userId=$BID")
[ "$S2" = "403" ] || { echo "❌ expected 403, got $S2"; cat /tmp/n2.out; exit 1; }

echo "[N3] token A but senderId B must be 403"
C=$(curl -s -X POST "$BASE_URL/conversations" -H 'Content-Type: application/json' -H "Authorization: Bearer $ATK" -d "{\"creatorId\":$AID,\"type\":\"DIRECT\",\"title\":null,\"memberIds\":[$BID]}")
CID=$(json_num "$C" "id")
[ -n "${CID:-}" ] || { echo "❌ cannot prepare conversation"; echo "$C"; exit 1; }
S3=$(curl -s -o /tmp/n3.out -w '%{http_code}' -X POST "$BASE_URL/conversations/$CID/messages" -H 'Content-Type: application/json' -H "Authorization: Bearer $ATK" -d "{\"senderId\":$BID,\"text\":\"bad\",\"clientMsgId\":\"neg-1\"}")
[ "$S3" = "403" ] || { echo "❌ expected 403, got $S3"; cat /tmp/n3.out; exit 1; }

BC=$(curl -s -X POST "$BASE_URL/conversations" -H 'Content-Type: application/json' -H "Authorization: Bearer $BTK" -d "{\"creatorId\":$BID,\"type\":\"DIRECT\",\"title\":null,\"memberIds\":[$CID3]}")
BAD_CID=$(json_num "$BC" "id")
[ -n "${BAD_CID:-}" ] || { echo "❌ cannot create B-C conversation"; echo "$BC"; exit 1; }

export ATK BAD_CID BASE_URL

echo "[N4] ws subscribe to non-member conversation must fail"
WS_OUT=$(cd /opt/raize/chat-realtime/frontend && node - <<'NODE'
const { Client } = require('@stomp/stompjs');
const WebSocket = require('ws');

const base = process.env.BASE_URL || 'http://localhost:8080';
const wsUrl = base.replace(/^http/, 'ws') + '/ws';
const token = process.env.ATK;
const badCid = process.env.BAD_CID;

let done = false;
const finish = (ok, msg) => {
  if (done) return;
  done = true;
  try { client.deactivate(); } catch {}
  console.log(ok ? 'OK' : ('FAIL ' + msg));
  process.exit(ok ? 0 : 1);
};

const client = new Client({
  brokerURL: wsUrl,
  reconnectDelay: 0,
  connectHeaders: { Authorization: `Bearer ${token}` },
  webSocketFactory: () => new WebSocket(wsUrl),
});

client.onConnect = () => {
  try {
    client.subscribe(`/topic/conversations/${badCid}`, () => {});
  } catch (e) {
    finish(true, 'subscribe blocked');
    return;
  }
  setTimeout(() => finish(false, 'subscribe unexpectedly succeeded'), 1200);
};

client.onStompError = () => finish(true, 'broker rejected');
client.onWebSocketClose = () => finish(true, 'socket closed');
client.onWebSocketError = () => finish(true, 'socket error');

setTimeout(() => finish(false, 'timeout'), 3500);
client.activate();
NODE
)
if echo "$WS_OUT" | grep -q '^OK'; then
  echo "✅ NEGATIVE AUTH SMOKE PASS"
else
  echo "❌ ws negative failed"
  echo "$WS_OUT"
  exit 1
fi
