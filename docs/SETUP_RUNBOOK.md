# Chat Realtime — Setup & Runbook

---

## 1. Setup lần đầu

### Backend

```bash
cd /opt/raize/chat-realtime/backend
./mvnw -q -DskipTests package
```

### Frontend

```bash
cd /opt/raize/chat-realtime/frontend
npm install
npm run build
```

---

## 2. Chạy local/dev

### Start backend jar mode

```bash
cd /opt/raize/chat-realtime/backend
./mvnw -q -DskipTests package
lsof -i :8080 -t | xargs -r kill -9
nohup java -Xms128m -Xmx384m -jar target/chat-realtime-0.0.1-SNAPSHOT.jar > /tmp/chat-realtime-backend.log 2>&1 &
```

### Start frontend dev

```bash
cd /opt/raize/chat-realtime/frontend
npm run dev -- --host 0.0.0.0 --port 5173
```

---

## 3. Kiểm tra service

### Backend port

```bash
lsof -i :8080
```

### Frontend port

```bash
lsof -i :5173
```

### Backend log

```bash
tail -n 120 /tmp/chat-realtime-backend.log
```

---

## 4. Test bắt buộc sau khi sửa code

### Frontend

```bash
cd /opt/raize/chat-realtime/frontend
npm run build
```

### Backend

```bash
cd /opt/raize/chat-realtime/backend
./mvnw -q test
```

### Smoke

```bash
cd /opt/raize/chat-realtime
./smoke.sh
./smoke-auth-negative.sh
```

### WS

```bash
cd /opt/raize/chat-realtime
P95_MAX_MS=1000 MODE=ws ./ws-latency-smoke.sh
```

---

## 5. Debug nhanh theo lỗi

### 500 khi gọi API

```bash
grep -a -n "ERROR\|Exception" /tmp/chat-realtime-backend.log | tail -n 100
```

Các nguyên nhân thường gặp:

- Backend đang chạy code cũ.
- Chưa restart jar sau khi build.
- Endpoint mới chưa deploy.
- Validation chưa map đúng sang 400.

### 401

- Thiếu `Authorization` header.
- Token không dạng `Bearer dev-token-user-{id}`.

### 403

- Token user không khớp query/body.
- WS subscribe topic không thuộc quyền.

### WS fallback

- Backend chưa chạy `/ws`.
- Token không gửi qua STOMP CONNECT.
- Proxy/firewall chặn websocket.
- Subscribe bị interceptor reject.

### Frontend trắng/lỗi build

```bash
cd frontend
npm run build
```

Xem TypeScript error trước.

---

## 6. Quy trình deploy thay đổi backend

1. Sửa code.
2. Backup file trước/sau theo quy tắc project.
3. Test backend:

```bash
./mvnw -q test
```

4. Package:

```bash
./mvnw -q -DskipTests package
```

5. Restart jar:

```bash
lsof -i :8080 -t | xargs -r kill -9
nohup java -Xms128m -Xmx384m -jar target/chat-realtime-0.0.1-SNAPSHOT.jar > /tmp/chat-realtime-backend.log 2>&1 &
```

6. Run smoke.

---

## 7. Quy trình deploy thay đổi frontend

1. Sửa `App.tsx`/`App.css`.
2. Backup file.
3. Build:

```bash
npm run build
```

4. Nếu đang chạy Vite dev, refresh browser.

---

## 8. Smoke scripts đang kiểm tra gì?

### `smoke.sh`

- Login users.
- Create DIRECT idempotent.
- Send message.
- Unread count > 0.
- Mark read.
- Unread = 0.
- Direct peer endpoint.

### `smoke-auth-negative.sh`

- Missing token -> 401.
- Query user mismatch -> 403.
- Sender mismatch -> 403.
- WS subscribe non-member -> reject.

### `ws-latency-smoke.sh`

- Tạo WS client.
- Subscribe topic.
- Gửi messages.
- Đo latency p50/p95/max.

---

## 9. Lệnh curl mẫu

### Login

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"smoke_a","password":"123456"}'
```

### List conversations

```bash
curl -s 'http://localhost:8080/conversations?userId=5&limit=50' \
  -H 'Authorization: Bearer dev-token-user-5'
```

### Send message

```bash
curl -s -X POST http://localhost:8080/conversations/14/messages \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer dev-token-user-5' \
  -d '{"senderId":5,"text":"hello","clientMsgId":"manual-1"}'
```

### Mark read

```bash
curl -s -X POST http://localhost:8080/conversations/14/messages/mark-read \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer dev-token-user-5' \
  -d '{"userId":5}'
```
