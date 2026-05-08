# Chat Realtime — Code Commentary

Tài liệu này giải thích từng cụm code chính đang có trong dự án. Mục tiêu là đọc code không bị lạc flow.

---

## 1. Backend entrypoint

### `ChatRealtimeApplication.java`

Vai trò:

- Entry point Spring Boot.
- Khi chạy jar, class này boot toàn bộ application context.

Flow:

```text
main()
  -> SpringApplication.run
  -> scan components
  -> start Tomcat
  -> start JPA/Flyway/WebSocket broker
```

---

## 2. Common response/error

### `ApiResponse.java`

Vai trò:

- Chuẩn hóa response JSON.
- FE luôn đọc `success`, `data`, `message`.

Dùng để tránh mỗi endpoint trả một kiểu khác nhau.

### `GlobalExceptionHandler.java`

Vai trò:

- Map exception sang HTTP status.
- `UnauthorizedException` -> 401.
- `ForbiddenException` -> 403.
- validation/illegal argument -> 400.
- unknown -> 500.

Tại sao cần?

- FE/smoke test cần status chuẩn.
- Tránh mọi lỗi đều thành 500.

---

## 3. Auth code

### `AuthController.java`

Vai trò:

- `/auth/register`
- `/auth/login`

Hiện là dev auth đơn giản.

Output token:

```text
dev-token-user-{id}
```

### `AuthUserResolver.java`

Vai trò:

- Đọc user id từ request.
- Ưu tiên request attribute `authUserId` do filter set.
- Fallback parse Authorization header.

### `DevTokenAuthFilter.java`

Vai trò:

- Parse token mỗi request.
- Gắn auth vào request/security context.

### `SecurityConfig.java`

Vai trò:

- Cấu hình Spring Security.
- Gắn `DevTokenAuthFilter` vào chain.
- Cho phép endpoint cần public như auth.

---

## 4. Conversation code

### `Conversation.java`

Entity đại diện bảng `conversations`.

Field quan trọng:

- `type`: DIRECT/GROUP.
- `title`: tên nhóm.
- `directPairKey`: chống trùng DIRECT.
- `lastMessageAt`: sort list.

### `ConversationMember.java`

Entity đại diện bảng `conversation_members`.

Field quan trọng:

- `conversationId`
- `userId`
- `isActive`

`isActive=false` nghĩa là user đã ẩn/xóa chat khỏi danh sách.

### `ConversationReadState.java`

Entity lưu user đã đọc đến đâu.

Dùng cho unread count.

### `ConversationRepository.java`

Query conversation.

Quan trọng:

- `findByDirectPairKey`

### `ConversationMemberRepository.java`

Query membership.

Quan trọng:

- `findByUserIdAndIsActiveTrue`
- `findByConversationIdAndUserIdAndIsActiveTrue`
- `findByConversationIdAndIsActiveTrue`
- `findByConversationIdAndUserId`

### `ConversationService.java`

Đây là business core của conversation.

#### `create(...)`

Flow:

```text
validate input
  ↓
normalize memberIds: bỏ null, <=0, creatorId
  ↓
Nếu DIRECT:
    build pairKey
    nếu tồn tại:
        upsert active member creator/peer
        return existing
  ↓
create new Conversation
  ↓
upsertActiveMember creator
  ↓
upsertActiveMember từng member
  ↓
return conversation
```

#### `upsertActiveMember(...)`

Ý nghĩa:

- Nếu row member chưa có -> tạo mới.
- Nếu đã có nhưng inactive -> set active lại.

Tại sao cần?

- Khi user xóa DIRECT, member inactive.
- Sau đó click chat lại, phải khôi phục conversation cũ.

#### `listByUserWithUnread(...)`

Flow:

```text
list conversations active của user
  ↓
messageRepository.countUnreadBatch(userId, conversationIds)
  ↓
map unread theo conversationId
  ↓
sort by lastMessageAt desc
  ↓
limit safe 1..200
  ↓
return DTO gồm unreadCount
```

Tại sao batch unread?

- Tránh N query cho N conversations.
- Scale tốt hơn.

#### `hideForUser(...)`

Flow:

```text
find active member
  ↓
set isActive=false
  ↓
save
```

Không hard-delete.

### `ConversationController.java`

Endpoint:

- `POST /conversations`
- `GET /conversations`
- `DELETE /conversations/{id}`
- `GET /conversations/{id}/direct-peer`

Controller luôn check auth mismatch trước khi gọi service.

---

## 5. Message code

### `Message.java`

Entity bảng messages.

Field quan trọng:

- `conversationId`
- `senderId`
- `seq`
- `contentJson`
- `createdAt`

### `MessageRepository.java`

Query chính:

- Lấy 50 message mới nhất.
- Lấy message mới hơn cursor.
- Lấy max seq.
- Batch count unread.

### `MessageService.java`

#### `send(...)`

Flow code:

```text
check sender active member
  ↓
validate text
  ↓
create Message
  ↓
set clientMsgId
  ↓
set contentJson
  ↓
set seq=maxSeq+1
  ↓
save
  ↓
update conversation.lastMessageAt
  ↓
WS broadcast selected conversation topic
  ↓
WS broadcast recipient user-list topic
  ↓
return saved
```

#### `list(...)`

Lấy 50 message mới nhất theo desc rồi sort asc để FE render đúng thứ tự.

#### `listSince(...)`

Nếu cursor invalid -> fallback `list`.
Nếu có cursor -> lấy message id lớn hơn cursor.

#### `markRead(...)`

Flow:

```text
check user active member
  ↓
get latest message
  ↓
if no message return
  ↓
find or create ConversationReadState
  ↓
set lastReadMessageId/latest seq
  ↓
save
```

### `MessageController.java`

Endpoint:

- `POST /conversations/{id}/messages`
- `GET /conversations/{id}/messages`
- `POST /conversations/{id}/messages/mark-read`

Auth guards:

- send: token user phải bằng `senderId`.
- mark-read: token user phải bằng `userId`.

---

## 6. WebSocket code

### `WsConfig.java`

Vai trò:

- Enable STOMP WebSocket.
- Endpoint `/ws`.
- Simple broker `/topic`.
- Application prefix `/app`.
- Register inbound channel interceptor.

### `WsAuthHandshakeInterceptor.java`

Vai trò:

- Đọc auth token khi WS handshake.
- Lưu `authUserId` vào session attributes.

### `WsSubscriptionAuthInterceptor.java`

Vai trò:

- Parse token khi CONNECT.
- Chặn subscribe trái phép.

Rules:

```text
/topic/conversations/{id}
  -> auth user phải là active member

/topic/users/{userId}/conversations
  -> auth user phải bằng userId trong topic
```

---

## 7. Frontend code

### `App()`

Flow:

```text
read localStorage.chat_user
  ↓
if null -> AuthScreen
else -> ChatScreen
```

### `AuthScreen`

State:

- `mode`: login/register.
- `username`, `displayName`, `password`.
- `msg`: error.

Submit:

```text
POST auth endpoint
  ↓
if success:
    save chat_user/chat_token
    onLogin(data)
else:
    show msg
```

### `ChatScreen`

State chính:

- conversations/users/messages.
- selectedId.
- group modal state.
- wsState.
- search/filter.

#### `loadUsers`

Gọi `/users`, render active users.

#### `loadConversations`

Gọi `/conversations`, sort list.

#### `markRead`

Gọi `/mark-read`, nếu success set unreadCount=0 local.

#### `resolveDirectNames`

Gọi `/direct-peer` để lấy tên user đối diện trong DIRECT.

#### `fetchMessages`

Gọi messages API.
Merge bằng Map theo id để tránh duplicate.

#### WebSocket `useEffect`

Tạo STOMP client.

Subscribe:

- user topic always.
- conversation topic nếu có selectedId.

Reconnect:

- custom exponential backoff.

#### `createDirect`

Click user -> tạo/mở DIRECT.

#### `createGroup`

Popup -> POST group -> close modal -> open group.

#### `send`

Submit message form.

#### `deleteConversation`

Swipe delete -> DELETE API -> remove local.

#### `chatRows`

Chuyển raw messages thành row render:

- separator nếu gap >= 5 phút.
- compact message cùng sender/cùng phút.
- avatar chỉ hiện ở cuối group.
- hover show time.

---

## 8. CSS code

### Layout chính

- `.chat-layout`: wrapper.
- `.board`: desktop grid 2 cards.
- `.left`: conversation card.
- `.right`: chat card.

### Mobile

Media query `max-width: 760px`:

- Home/list và chat screen tách nhau.
- `.chat-open` hiển thị chat.
- `.home-open` hiển thị list.

### Conversation row

- `.contact-row`: row thường.
- `.contact-row.unread`: highlight chưa đọc.
- `.badge`: badge đỏ unread.
- `.chat-swipe.open .contact-row`: swipe-left reveal delete.

### Group modal

- `.modal-backdrop`
- `.group-modal`
- `.picked-users`
- `.modal-user-list`
- `.create-group-submit`

### Messages

- `.msgs`: scroll container.
- `.msg-wrap.me`: tin của mình.
- `.msg.in` / `.msg.out`: bubble.
- `.time-separator`: phân cách thời gian.

---

## 9. Comment gợi ý để thêm vào code sau này

Nếu muốn comment trực tiếp trong code, nên comment theo “why” hơn là “what”.

Ví dụ tốt:

```java
// DIRECT conversations are idempotent: A->B and B->A must resolve to the same row.
// Re-activate memberships here because a user may have hidden the chat earlier.
```

Ví dụ không cần thiết:

```java
// Save conversation
conversationRepository.save(c);
```

Vì dòng code đã tự nói rõ “save”.
