# Chat Realtime MVP

- API contract (MVP): `GET /conversations?userId={id}&limit={n}` returns `[{ id, type, title, lastMessageAt, unreadCount }]`.
  - `limit` optional, default `50`, clamped server-side to range `1..200`.
- API contract (MVP): `GET /conversations/{conversationId}/direct-peer?userId={id}` returns `{ id, username, displayName }` for DIRECT peer.
- API contract (MVP): `POST /conversations/{conversationId}/messages/mark-read` body `{ userId }` marks latest as read for that user.
- Timezone rule: DB/JPA persist in UTC; frontend renders chat timestamps in `Asia/Ho_Chi_Minh` (+7).
- DIRECT behavior: backend idempotent by `direct_pair_key` so A->B and B->A reuse same conversation.
- FE behavior: login lands on Welcome (no auto-open chat), click user card opens DIRECT.
- Runbook: if backend fails with Flyway validate failed migration, run `./mvnw -Dflyway.url=... -Dflyway.user=... -Dflyway.password=... flyway:repair` then restart.
- Runbook: if `:8080` already in use, stop stale Java process (`lsof -i :8080`) before restart.
- MVP freeze note: keep polling flow (2.5s + backoff), avoid changing API response keys without FE update and smoke-test rerun.

## Ops: Conversations latency dashboard (quick)

Backend logs include:
- `conversations.list userId=<id> limit=<n> count=<n> tookMs=<ms>`

Quick summary (last 500 calls) for p50/p95/p99:

```bash
grep 'conversations.list' /tmp/chat-realtime-backend.log | tail -n 500 \
  | sed -n 's/.*tookMs=\([0-9][0-9]*\).*/\1/p' \
  | sort -n \
  | awk '{a[NR]=$1} END{ if(NR==0){print "no data"; exit} p50=a[int((NR+1)*0.50)]; p95=a[int((NR+1)*0.95)]; p99=a[int((NR+1)*0.99)]; printf("count=%d p50=%sms p95=%sms p99=%sms\n", NR, p50, p95, p99)}'
```

## WebSocket ops

- STOMP endpoint: `ws://<host>:8080/ws`
- Topic conversation: `/topic/conversations/{conversationId}`
- Frontend status badge:
  - `WS: connected` = realtime active
  - `WS: connecting` = handshake/reconnect
  - `WS: fallback` = polling fallback mode
- Reconnect strategy: exponential backoff + jitter (cap 30s), polling remains active as safety net.
- UI load guard: conversation reload on incoming WS messages is throttled (~1.2s) to avoid burst spikes.
- Polling adaptation: when WS is `connected`, message polling relaxes to ~5s; otherwise fallback uses normal backoff polling.
- Security: non-member subscribe is blocked by server-side interceptor.
- WS observability logs:
  - reject counter: `ws.subscribe.reject ... count=<n>`
  - accepted connect counter (sampled each 20): `ws.connect.accept count=<n>`

Quick counters:

```bash
echo "rejects=$(grep -c 'ws.subscribe.reject' /tmp/chat-realtime-backend.log || true)"
echo "accept_samples=$(grep -c 'ws.connect.accept' /tmp/chat-realtime-backend.log || true)"
```

Latency smoke (quick):

```bash
cd /opt/raize/chat-realtime
./ws-latency-smoke.sh                                      # MODE=rest (default)
MODE=ws ./ws-latency-smoke.sh                              # websocket delivery latency
P50_MAX_MS=180 P95_MAX_MS=300 MODE=ws ./ws-latency-smoke.sh
```

SLA gate:
- Script trả `exit 1` khi vi phạm ngưỡng `p50` hoặc `p95`.
- Default: `P50_MAX_MS=150`, `P95_MAX_MS=250`.

---

## Documentation Map

Tài liệu chi tiết A-Z của dự án nằm trong thư mục `docs/`:

- `docs/PROJECT_A_Z.md` — tổng quan đầy đủ: setup, kiến trúc, DB, API, WS, UI, test, vận hành, mở rộng.
- `docs/FLOW_GUIDE.md` — từng luồng sử dụng: login, tạo DIRECT/GROUP, gửi tin, unread, WS, delete, mobile.
- `docs/API_CONTRACT.md` — contract REST/WS, request/response, status 401/403/400/500.
- `docs/WS_REALTIME.md` — thiết kế WebSocket, topic, auth guard, reconnect, debug.
- `docs/SETUP_RUNBOOK.md` — lệnh setup/chạy/restart/test/debug.
- `docs/CODE_COMMENTARY.md` — giải thích từng file/code block chính và lý do thiết kế.

Khuyến nghị đọc theo thứ tự:

1. `docs/PROJECT_A_Z.md`
2. `docs/FLOW_GUIDE.md`
3. `docs/API_CONTRACT.md`
4. `docs/WS_REALTIME.md`
5. `docs/SETUP_RUNBOOK.md`
6. `docs/CODE_COMMENTARY.md`

