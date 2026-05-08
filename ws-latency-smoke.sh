#!/usr/bin/env sh
set -eu
BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_A="${SMOKE_USER_A:-smoke_a}"
USER_B="${SMOKE_USER_B:-smoke_b}"
PASS="${SMOKE_PASS:-123456}"
ROUNDS="${ROUNDS:-8}"
MODE="${MODE:-rest}" # rest | ws
P95_MAX_MS="${P95_MAX_MS:-250}"
P50_MAX_MS="${P50_MAX_MS:-150}"

json_num(){ echo "$1"|sed -n "s/.*\"$2\":\([0-9][0-9]*\).*/\1/p"; }
json_str(){ echo "$1"|sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }

login_or_register(){
  U="$1"; D="$2"
  L=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"$PASS\"}")
  ID=$(json_num "$L" userId)
  if [ -z "${ID:-}" ]; then
    curl -s -X POST "$BASE_URL/auth/register" -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"displayName\":\"$D\",\"password\":\"$PASS\"}" >/dev/null
    L=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$U\",\"password\":\"$PASS\"}")
    ID=$(json_num "$L" userId)
  fi
  TK=$(json_str "$L" token)
  echo "$ID|$TK"
}

A=$(login_or_register "$USER_A" "Smoke A")
B=$(login_or_register "$USER_B" "Smoke B")
AID=$(echo "$A"|cut -d'|' -f1); ATK=$(echo "$A"|cut -d'|' -f2)
BID=$(echo "$B"|cut -d'|' -f1)

C=$(curl -s -X POST "$BASE_URL/conversations" -H 'Content-Type: application/json' -H "Authorization: Bearer $ATK" -d "{\"creatorId\":$AID,\"type\":\"DIRECT\",\"title\":null,\"memberIds\":[$BID]}")
CID=$(json_num "$C" id)
[ -n "$CID" ] || { echo 'cannot create conversation'; exit 1; }

TMP=$(mktemp)
if [ "$MODE" = "ws" ]; then
  export BASE_URL ATK AID CID ROUNDS TMP
  (cd /opt/raize/chat-realtime/frontend && node - <<'NODE'
process.on('unhandledRejection', (e) => { console.error('unhandledRejection', e); process.exit(1); });
process.on('uncaughtException', (e) => { console.error('uncaughtException', e); process.exit(1); });
const { Client } = require('@stomp/stompjs');
const WebSocket = require('ws');
const fs = require('fs');

const base = process.env.BASE_URL || 'http://localhost:8080';
const rawWsUrl = base.replace(/^http/, 'ws') + '/ws';
const token = process.env.ATK;
const wsUrl = `${rawWsUrl}?access_token=${encodeURIComponent(token)}`;
const senderId = Number(process.env.AID);
const cid = Number(process.env.CID);
const rounds = Number(process.env.ROUNDS || '8');
const tmp = process.env.TMP;

const sendHttp = async (i, t0) => {
  const res = await fetch(`${base}/conversations/${cid}/messages`, {
    method: 'POST', headers: { 'Content-Type':'application/json', 'Authorization': `Bearer ${token}` },
    body: JSON.stringify({ senderId, text: `lat-${i}-${t0}`, clientMsgId: `lat-${i}-${t0}` })
  });
  if (!res.ok) throw new Error('send failed');
};

const starts = new Map();
let i = 0;
let done = 0;
const client = new Client({reconnectDelay: 0, connectHeaders: { Authorization: `Bearer ${token}` }, webSocketFactory: () => new WebSocket(wsUrl)});
client.onConnect = () => {
  client.subscribe(`/topic/conversations/${cid}`, (msg) => {
    try {
      const m = JSON.parse(msg.body);
      const t = starts.get(m.clientMsgId);
      if (t) {
        const d = Date.now() - t;
        fs.appendFileSync(tmp, String(d) + '\n');
        starts.delete(m.clientMsgId);
        done++;
        if (done >= rounds) { client.deactivate(); process.exit(0); }
      }
    } catch {}
  });

  const tick = async () => {
    i++;
    const t0 = Date.now();
    const id = `lat-${i}-${t0}`;
    starts.set(id, t0);
    await sendHttp(i, t0);
    if (i < rounds) setTimeout(() => tick().catch(()=>process.exit(1)), 120);
  };
  tick().catch(()=>process.exit(1));
};
client.onStompError = (f) => { console.error('stompError', f?.headers, f?.body); process.exit(1); };
client.onWebSocketError = (e) => { console.error('wsError', e?.message || e); process.exit(1); };
client.onWebSocketClose = (e) => { console.error('wsClose', e?.code, e?.reason); process.exit(1); };
setTimeout(()=>{ console.error('timeout'); process.exit(1); }, 15000);
client.activate();
NODE
  )
else
  i=1
  while [ "$i" -le "$ROUNDS" ]; do
    T0=$(date +%s%3N)
    curl -s -X POST "$BASE_URL/conversations/$CID/messages" -H 'Content-Type: application/json' -H "Authorization: Bearer $ATK" -d "{\"senderId\":$AID,\"text\":\"lat-$i-$T0\",\"clientMsgId\":\"lat-$i-$T0\"}" >/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      R=$(curl -s -H "Authorization: Bearer $ATK" "$BASE_URL/conversations/$CID/messages")
      if echo "$R" | grep -q "lat-$i-$T0"; then
        T1=$(date +%s%3N); echo $((T1-T0)) >> "$TMP"; break
      fi
      sleep 0.15
    done
    i=$((i+1))
  done
fi

sort -n "$TMP" > "$TMP.s"
N=$(wc -l < "$TMP.s" | tr -d ' ')
[ "$N" -gt 0 ] || { echo 'no samples'; exit 1; }
P50_IDX=$(( (N+1)*50/100 )); [ "$P50_IDX" -lt 1 ] && P50_IDX=1
P95_IDX=$(( (N+1)*95/100 )); [ "$P95_IDX" -lt 1 ] && P95_IDX=1
P50=$(sed -n "${P50_IDX}p" "$TMP.s")
P95=$(sed -n "${P95_IDX}p" "$TMP.s")
MAX=$(tail -n 1 "$TMP.s")
echo "Latency ($MODE): count=$N p50=${P50}ms p95=${P95}ms max=${MAX}ms thresholds(p50<=${P50_MAX_MS}, p95<=${P95_MAX_MS})"
if [ "$P50" -gt "$P50_MAX_MS" ] || [ "$P95" -gt "$P95_MAX_MS" ]; then
  echo "❌ LATENCY SLA FAIL ($MODE)"
  rm -f "$TMP" "$TMP.s"
  exit 1
fi

echo "✅ LATENCY SLA PASS ($MODE)"
rm -f "$TMP" "$TMP.s"
