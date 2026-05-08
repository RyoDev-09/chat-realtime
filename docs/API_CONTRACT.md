# Chat Realtime — API Contract

Base URL dev:

```text
http://localhost:8080
```

Auth header cho chat APIs:

```http
Authorization: Bearer dev-token-user-{userId}
```

Response wrapper chuẩn:

```json
{
  "success": true,
  "data": {},
  "message": "OK"
}
```

---

## 1. Auth

### Register

```http
POST /auth/register
Content-Type: application/json
```

Body:

```json
{
  "username": "user_a",
  "displayName": "User A",
  "password": "123456"
}
```

Success:

```json
{
  "success": true,
  "data": {
    "token": "dev-token-user-5",
    "userId": 5,
    "username": "user_a",
    "displayName": "User A"
  },
  "message": "register success"
}
```

### Login

```http
POST /auth/login
Content-Type: application/json
```

Body:

```json
{
  "username": "user_a",
  "password": "123456"
}
```

---

## 2. Users

### List users

```http
GET /users?excludeUserId=5
Authorization: Bearer dev-token-user-5
```

Ý nghĩa:

- Lấy danh sách user để hiển thị active users strip.
- `excludeUserId` để không hiển thị chính mình.

---

## 3. Conversations

### Create DIRECT

```http
POST /conversations
Authorization: Bearer dev-token-user-5
Content-Type: application/json
```

```json
{
  "creatorId": 5,
  "type": "DIRECT",
  "title": null,
  "memberIds": [6]
}
```

Rules:

- `creatorId` phải khớp token user.
- DIRECT phải có đúng 1 member ngoài creator.
- Nếu DIRECT giữa 5 và 6 đã tồn tại, backend trả existing conversation.
- Nếu member từng xóa/ẩn chat, backend reactivate membership.

### Create GROUP

```http
POST /conversations
Authorization: Bearer dev-token-user-5
Content-Type: application/json
```

```json
{
  "creatorId": 5,
  "type": "GROUP",
  "title": "Team A",
  "memberIds": [6, 7]
}
```

Rules:

- GROUP phải có ít nhất 2 member ngoài creator.

### List conversations

```http
GET /conversations?userId=5&limit=50
Authorization: Bearer dev-token-user-5
```

Response data item:

```json
{
  "id": 14,
  "type": "DIRECT",
  "title": null,
  "lastMessageAt": "2026-05-08T17:27:05.513",
  "unreadCount": 2
}
```

Rules:

- `limit` default 50.
- Clamp: min 1, max 200.
- Chỉ trả conversation mà user có `is_active=true`.
- Sort mới nhất lên đầu.
- `unreadCount` là source-of-truth từ backend.

### Hide/delete conversation for current user

```http
DELETE /conversations/14?userId=5
Authorization: Bearer dev-token-user-5
```

Success:

```json
{
  "success": true,
  "data": true,
  "message": "conversation deleted"
}
```

Ý nghĩa:

- Không xóa hard conversation/messages.
- Chỉ set `conversation_members.is_active=false` cho user hiện tại.

### Direct peer

```http
GET /conversations/14/direct-peer?userId=5
Authorization: Bearer dev-token-user-5
```

Response:

```json
{
  "success": true,
  "data": {
    "id": 6,
    "username": "user_b",
    "displayName": "User B"
  },
  "message": "OK"
}
```

Ý nghĩa:

- DIRECT không có title nên FE dùng endpoint này để lấy tên người còn lại.

---

## 4. Messages

### List latest messages

```http
GET /conversations/14/messages
Authorization: Bearer dev-token-user-5
```

Response item:

```json
{
  "id": 100,
  "conversationId": 14,
  "senderId": 5,
  "contentJson": "{\"text\":\"hello\"}",
  "contentType": "TEXT",
  "seq": 1,
  "createdAt": "2026-05-08T17:27:05.513"
}
```

### List since cursor

```http
GET /conversations/14/messages?cursorId=100
Authorization: Bearer dev-token-user-5
```

Ý nghĩa:

- Chỉ lấy message `id > cursorId`.
- Dùng cho polling incremental.

### Send message

```http
POST /conversations/14/messages
Authorization: Bearer dev-token-user-5
Content-Type: application/json
```

```json
{
  "senderId": 5,
  "text": "hello",
  "clientMsgId": "web-171..."
}
```

Rules:

- `senderId` phải khớp token user.
- Sender phải là active member.
- `text` không được blank.

Side effects:

- Insert message.
- Update `conversation.lastMessageAt`.
- WS broadcast conversation topic.
- WS broadcast user-list topic cho recipients.

### Mark read

```http
POST /conversations/14/messages/mark-read
Authorization: Bearer dev-token-user-5
Content-Type: application/json
```

```json
{
  "userId": 5
}
```

Side effects:

- Upsert `conversation_read_state`.
- Set `lastReadMessageId` bằng message mới nhất.

---

## 5. Status/error contract

### 401

Thiếu/invalid token.

### 403

Token user không khớp `userId`, `creatorId`, hoặc `senderId`.

### 400

Input invalid.

### 500

Bug không mong muốn, cần xem log.

---

## 6. WebSocket contract

Endpoint:

```text
/ws
```

Connect header:

```text
Authorization: Bearer dev-token-user-{id}
```

### Topic conversation

```text
/topic/conversations/{conversationId}
```

Payload: `Message`.

Ai được subscribe?

- Active member của conversation.

### Topic user conversations

```text
/topic/users/{userId}/conversations
```

Payload: `Message`.

Ai được subscribe?

- Chỉ chính user đó.

Ứng dụng:

- Realtime unread badge.
- Realtime highlight list row.
- Move conversation lên đầu.
