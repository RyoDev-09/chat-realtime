# Chat Realtime — WebSocket Realtime Design

---

## 1. Mục tiêu WS

WS giải quyết 2 nhu cầu:

1. Tin nhắn trong chat đang mở phải hiện gần như tức thì.
2. Danh sách conversation phải realtime unread/highlight khi có tin mới ở chat chưa mở.

---

## 2. Endpoint và protocol

Endpoint:

```text
/ws
```

Protocol:

```text
STOMP over WebSocket
```

Frontend lib:

```text
@stomp/stompjs
```

Backend:

```text
Spring WebSocket Message Broker
SimpleBroker /topic
Application prefix /app
```

---

## 3. Topic map

### 3.1 Conversation topic

```text
/topic/conversations/{conversationId}
```

Payload:

```json
Message
```

Dùng cho:

- Chat đang mở.
- Append message realtime.
- Auto-scroll bottom.

### 3.2 User conversation-list topic

```text
/topic/users/{userId}/conversations
```

Payload:

```json
Message
```

Dùng cho:

- Badge unread realtime.
- Highlight row chưa đọc.
- Move conversation lên đầu.

---

## 4. Backend broadcast flow

Trong `MessageService.send`:

```text
message saved
  ↓
convertAndSend('/topic/conversations/' + conversationId, saved)
  ↓
for each active member except sender:
    convertAndSend('/topic/users/' + member.userId + '/conversations', saved)
```

Tại sao không gửi user topic cho sender?

- Sender đã biết mình vừa gửi.
- Sender không cần tăng unread của chính mình.

---

## 5. Frontend subscribe flow

Khi `ChatScreen` mount hoặc `selectedId` thay đổi:

```text
Create STOMP client
  ↓
CONNECT with Authorization header
  ↓
onConnect:
   subscribe user topic always
   if selectedId -> subscribe selected conversation topic
```

### 5.1 User topic handler

```text
incoming Message
  ↓
if incoming.conversationId != selectedId:
    update conversation local:
        unreadCount += 1
        lastMessageAt = incoming.createdAt
    sort conversation desc
  ↓
throttle loadConversations()
```

Tại sao vẫn loadConversations?

- Local increment nhanh để UI realtime.
- Backend source-of-truth vẫn phải sync để tránh lệch.

### 5.2 Conversation topic handler

```text
incoming Message
  ↓
if message id exists -> ignore duplicate
  ↓
append + sort
  ↓
scroll bottom
  ↓
if sender != current user and tab visible:
    markRead(selectedId)
  ↓
throttle loadConversations()
```

---

## 6. Reconnect strategy

FE tắt reconnect tự động của STOMP:

```ts
reconnectDelay: 0
```

Tự quản lý reconnect:

```text
onWebSocketClose/onStompError/onWebSocketError
  ↓
wsRetryRef += 1
  ↓
baseDelay = min(30000, 1000 * 2^retry)
  ↓
+ jitter random 0..700ms
  ↓
client.activate()
```

Tại sao tự quản?

- Dễ kiểm soát trạng thái `WS: fallback`.
- Dễ giới hạn backoff.
- Tránh reconnect quá dày.

---

## 7. Security

### 7.1 Handshake auth

`WsAuthHandshakeInterceptor` đọc token từ:

- Header `Authorization`.
- Hoặc query `access_token` nếu client không gửi được header.

Lưu `authUserId` vào session attributes.

### 7.2 CONNECT auth

`WsSubscriptionAuthInterceptor` cũng parse native STOMP header `Authorization` ở CONNECT.

### 7.3 SUBSCRIBE guard

#### Conversation topic

```text
/topic/conversations/{conversationId}
```

Rule:

```text
user phải là active member của conversation
```

#### User topic

```text
/topic/users/{userId}/conversations
```

Rule:

```text
dest userId phải bằng authUserId
```

---

## 8. Test WS thủ công

### UI test

1. Mở 2 tab/browser.
2. Login user A và user B.
3. B để ở danh sách chat, không mở chat A.
4. A gửi tin cho B.
5. B phải thấy:
   - badge đỏ tăng.
   - row highlight.
   - conversation lên đầu.
6. B mở chat.
7. Badge mất.

### Script test

```bash
cd /opt/raize/chat-realtime
P95_MAX_MS=1000 MODE=ws ./ws-latency-smoke.sh
```

### Auth negative

```bash
./smoke-auth-negative.sh
```

---

## 9. Debug WS

### Không connected

Check:

```bash
tail -f /tmp/chat-realtime-backend.log
```

Tìm:

```text
ws.subscribe.reject
```

### Badge không realtime

Check:

1. FE có `WS: connected` không?
2. Backend có broadcast user topic không?
3. User nhận có active member không?
4. FE có đang subscribe `/topic/users/{auth.userId}/conversations` không?
5. Có bị selectedId trùng conversation không? Nếu đang mở chat thì unread không tăng vì mark-read.

### Message bị duplicate

Đã chống bằng:

```ts
if(prev.some(x=>x.id===incoming.id)) return prev
```

Nếu vẫn duplicate, kiểm tra message id backend có unique và payload có id chưa.
