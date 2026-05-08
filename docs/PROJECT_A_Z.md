# Chat Realtime — Tài liệu A-Z dự án

> Mục tiêu tài liệu: giúp người mới đọc hiểu dự án từ setup, kiến trúc, database, API, realtime WebSocket, polling fallback, unread/read, UI flow, test, vận hành, và cách mở rộng.
>
> Trạng thái tài liệu: viết theo code hiện tại trong `/opt/raize/chat-realtime` sau các phase MVP + WS + Messenger-like UI.

---

## 0. Tóm tắt 30 giây

`chat-realtime` là app chat realtime gồm:

- **Backend**: Spring Boot + JPA + MySQL + Flyway + STOMP WebSocket.
- **Frontend**: React + TypeScript + Vite + STOMP client.
- **Auth dev**: token dạng `dev-token-user-{id}`.
- **Chat types**:
  - `DIRECT`: chat 1-1, idempotent bằng `directPairKey`.
  - `GROUP`: chat nhóm nhiều user.
- **Realtime**:
  - Message trong chat đang mở: `/topic/conversations/{conversationId}`.
  - Badge unread/list realtime: `/topic/users/{userId}/conversations`.
- **Fallback**:
  - Poll messages mỗi ~2.5s khi fallback, relax ~5s khi WS connected.
  - Poll conversation list mỗi 5s.
- **Unread source of truth**: backend tính từ bảng `conversation_read_state` + `messages`.

---

## 1. Cấu trúc thư mục

```text
/opt/raize/chat-realtime
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/chat_realtime/
│       │   ├── ChatRealtimeApplication.java
│       │   ├── auth/
│       │   ├── common/
│       │   ├── config/
│       │   ├── conversation/
│       │   ├── message/
│       │   ├── user/
│       │   └── ws/
│       └── resources/
│           ├── application.properties
│           └── db/migration/
├── frontend/
│   ├── package.json
│   └── src/
│       ├── App.tsx
│       ├── App.css
│       └── main.tsx
├── smoke.sh
├── smoke-auth-negative.sh
├── ws-latency-smoke.sh
└── README.md
```

### Vì sao chia như vậy?

- `backend/auth`: xử lý login/register và auth dev-token.
- `backend/conversation`: quản lý DIRECT/GROUP, member, unread list.
- `backend/message`: gửi/list/mark-read message.
- `backend/ws`: cấu hình và guard WebSocket.
- `frontend/src/App.tsx`: hiện tại gom toàn bộ UI/logic vào một file để MVP nhanh.
- `frontend/src/App.css`: style Messenger-like dashboard/mobile.

> Khuyến nghị dài hạn: khi dự án lớn hơn, tách `App.tsx` thành `components/`, `hooks/`, `api/`, `types/`.

---

## 2. Setup môi trường từ A-Z

### 2.1 Yêu cầu hệ thống

- Java 21.
- Maven wrapper có sẵn: `backend/mvnw`.
- Node.js 22+.
- MySQL 8.
- Port backend: `8080`.
- Port frontend dev: `5173`.

### 2.2 Database

Backend dùng MySQL database `chat_app`.

Kiểm tra cấu hình tại:

```text
backend/src/main/resources/application.properties
```

Các migration chạy bằng Flyway:

```text
backend/src/main/resources/db/migration/V1__init_chat.sql
backend/src/main/resources/db/migration/V2__direct_pair_key_and_constraints.sql
backend/src/main/resources/db/migration/V3__dedupe_direct_conversations.sql
```

Khi backend start:

1. Spring Boot boot app.
2. Flyway validate migration.
3. Flyway migrate nếu DB chưa có version mới.
4. JPA/Hibernate map entity.
5. Tomcat mở port `8080`.
6. STOMP broker khởi động.

### 2.3 Chạy backend

Khuyến nghị trên VPS hiện tại: chạy jar mode vì `spring-boot:run` từng bị kill `137`.

```bash
cd /opt/raize/chat-realtime/backend
./mvnw -q -DskipTests package
lsof -i :8080 -t | xargs -r kill -9
nohup java -Xms128m -Xmx384m -jar target/chat-realtime-0.0.1-SNAPSHOT.jar > /tmp/chat-realtime-backend.log 2>&1 &
```

Kiểm tra log:

```bash
tail -f /tmp/chat-realtime-backend.log
```

### 2.4 Chạy frontend

```bash
cd /opt/raize/chat-realtime/frontend
npm install
npm run dev -- --host 0.0.0.0 --port 5173
```

Mở:

```text
http://<server-ip>:5173
```

### 2.5 Build frontend

```bash
cd /opt/raize/chat-realtime/frontend
npm run build
```

### 2.6 Test backend

```bash
cd /opt/raize/chat-realtime/backend
./mvnw -q test
```

### 2.7 Smoke test full flow

```bash
cd /opt/raize/chat-realtime
./smoke.sh
./smoke-auth-negative.sh
```

### 2.8 Test WS latency

```bash
cd /opt/raize/chat-realtime
P95_MAX_MS=1000 MODE=ws ./ws-latency-smoke.sh
```

---

## 3. Kiến trúc tổng thể

```text
Browser React
  │
  ├── REST HTTP JSON
  │     ├── /auth/register
  │     ├── /auth/login
  │     ├── /users
  │     ├── /conversations
  │     └── /conversations/{id}/messages
  │
  └── WebSocket STOMP /ws
        ├── subscribe /topic/conversations/{conversationId}
        └── subscribe /topic/users/{userId}/conversations

Spring Boot Backend
  │
  ├── Controllers: validate/auth/API response
  ├── Services: business logic
  ├── Repositories: DB query
  ├── WS interceptors: auth subscribe
  └── MySQL: users/conversations/members/messages/read_state
```

### Tại sao vừa dùng REST vừa dùng WS?

- REST dễ kiểm soát, dễ test, dùng cho CRUD, initial load, fallback.
- WS dùng cho realtime push.
- Nếu WS lỗi, app vẫn hoạt động nhờ polling.

### Tại sao giữ polling fallback?

- WS có thể fail do proxy/firewall/network.
- Polling bảo đảm message/list vẫn cập nhật.
- MVP ổn định hơn, không phụ thuộc 100% vào WS.

---

## 4. Database model

### 4.1 `users`

Lưu user đăng nhập/register.

Vai trò:

- Auth login/register.
- Hiển thị danh sách active users.
- Resolve tên peer trong DIRECT.

### 4.2 `conversations`

Lưu conversation chính.

Các field quan trọng:

- `id`: ID conversation.
- `type`: `DIRECT` hoặc `GROUP`.
- `title`: tên nhóm, DIRECT thường null.
- `created_by`: user tạo.
- `direct_pair_key`: khóa cặp DIRECT, ví dụ `5:6`.
- `last_message_at`: sort conversation mới nhất lên đầu.

### 4.3 `conversation_members`

Lưu membership user-conversation.

Các field quan trọng:

- `conversation_id`
- `user_id`
- `is_active`

Ứng dụng:

- Conversation list chỉ lấy `is_active=true`.
- Xóa chat hiện tại là **soft-delete per user**: set `is_active=false`.
- Khi tạo lại DIRECT cũ, service reactivate member.

### 4.4 `messages`

Lưu tin nhắn.

Các field quan trọng:

- `conversation_id`
- `sender_id`
- `seq`
- `content_type`
- `content_json`
- `created_at`

`content_json` hiện dạng:

```json
{"text":"hello"}
```

### 4.5 `conversation_read_state`

Lưu user đọc đến message nào.

Các field quan trọng:

- `conversation_id`
- `user_id`
- `last_read_message_id`
- `last_read_seq`

Unread count được tính bằng:

```text
messages trong conversation có id > last_read_message_id và sender_id != currentUser
```

---

## 5. Backend layers

### 5.1 Controller làm gì?

Controller nhận HTTP request, xử lý:

1. Parse params/body.
2. Validate DTO bằng `@Valid`, `@NotNull`, `@NotBlank`.
3. Resolve auth user từ request.
4. Check userId/senderId mismatch.
5. Gọi service.
6. Wrap response bằng `ApiResponse`.

### 5.2 Service làm gì?

Service chứa business logic:

- Tạo DIRECT/GROUP.
- Idempotent DIRECT.
- Reactivate member khi mở lại DIRECT đã ẩn.
- Gửi message.
- Update `lastMessageAt`.
- Broadcast WS.
- Mark read.
- Tính unread list.

### 5.3 Repository làm gì?

Repository chỉ truy vấn DB:

- `findByDirectPairKey`
- `findByConversationIdAndUserIdAndIsActiveTrue`
- `countUnreadBatch`
- `findTop50ByConversationId...`

---

## 6. Auth flow

### 6.1 Register

Frontend gọi:

```http
POST /auth/register
Content-Type: application/json

{
  "username":"ryo",
  "displayName":"Ryo",
  "password":"123456"
}
```

Backend trả:

```json
{
  "success": true,
  "data": {
    "token": "dev-token-user-5",
    "userId": 5,
    "username": "ryo",
    "displayName": "Ryo"
  },
  "message": "register success"
}
```

Frontend lưu:

```text
localStorage.chat_user
localStorage.chat_token
```

### 6.2 Login

Tương tự register nhưng endpoint:

```http
POST /auth/login
```

### 6.3 Auth token dùng thế nào?

Frontend gửi header cho chat APIs:

```http
Authorization: Bearer dev-token-user-5
```

`AuthUserResolver` parse token để lấy `userId=5`.

### 6.4 Tại sao cần check `userId mismatch`?

Nếu token là user A nhưng body/query gửi `userId` của user B, request phải bị chặn.

Ví dụ:

```text
Authorization: Bearer dev-token-user-5
?userId=6
```

Kết quả đúng:

```http
403 Forbidden
```

---

## 7. Conversation flow

### 7.1 Load conversation list

Frontend gọi:

```http
GET /conversations?userId={auth.userId}&limit=50
Authorization: Bearer dev-token-user-{id}
```

Backend flow:

```text
ConversationController.list
  -> AuthUserResolver.resolveUserId
  -> check token user == query userId
  -> ConversationService.listByUserWithUnread
       -> memberRepository.findByUserIdAndIsActiveTrue
       -> conversationRepository.findAllById
       -> messageRepository.countUnreadBatch
       -> sort by lastMessageAt desc
       -> clamp limit 1..200
  -> ApiResponse.ok(data)
```

Frontend dùng data để render list.

### 7.2 Tạo DIRECT chat

Frontend click avatar/card user.

Request:

```http
POST /conversations
Authorization: Bearer dev-token-user-5
Content-Type: application/json

{
  "creatorId": 5,
  "type": "DIRECT",
  "title": null,
  "memberIds": [6]
}
```

Backend flow:

```text
ConversationController.create
  -> auth check creatorId == token user
  -> ConversationService.create
      -> validate DIRECT phải có đúng 1 member khác creator
      -> buildDirectPairKey(5,6) = "5:6"
      -> nếu tồn tại conversation directPairKey="5:6":
            upsertActiveMember(creator)
            upsertActiveMember(peer)
            return existing
      -> nếu chưa tồn tại:
            tạo Conversation
            tạo/active member creator
            tạo/active member peer
            return new conversation
```

Tại sao cần `directPairKey`?

- User A click B nhiều lần không tạo nhiều chat trùng.
- User B click A cũng trả cùng conversation.

### 7.3 Tạo GROUP chat

Frontend bấm `＋`, popup hiện:

1. Ô nhập tên nhóm.
2. Search user.
3. Danh sách user đã chọn.
4. Danh sách user phù hợp search.
5. Click user dưới -> đẩy lên selected, biến mất khỏi list dưới.
6. Bấm tạo nhóm.

Request:

```http
POST /conversations
Authorization: Bearer dev-token-user-5
Content-Type: application/json

{
  "creatorId": 5,
  "type": "GROUP",
  "title": "Team A",
  "memberIds": [6,7]
}
```

Backend yêu cầu GROUP phải có ít nhất 2 member ngoài creator.

### 7.4 Xóa conversation khỏi danh sách

Frontend swipe-left row, hiện icon delete, bấm icon.

Request:

```http
DELETE /conversations/{conversationId}?userId=5
Authorization: Bearer dev-token-user-5
```

Backend flow:

```text
ConversationController.hide
  -> auth check userId == token user
  -> ConversationService.hideForUser
      -> tìm active member row
      -> set isActive=false
      -> save
```

Tại sao không hard-delete?

- Nếu hard-delete conversation/messages thì ảnh hưởng thành viên khác.
- Soft-delete per user giống Messenger: chỉ ẩn khỏi danh sách của người đó.

---

## 8. Message flow

### 8.1 Load messages khi mở chat

Frontend khi `selectedId` đổi:

```text
setMessages([])
fetchMessages(selectedId)
markRead(selectedId)
scrollToBottom(false)
```

Request:

```http
GET /conversations/{conversationId}/messages
Authorization: Bearer dev-token-user-5
```

Backend trả tối đa 50 message mới nhất, sau đó sort asc để FE render từ cũ đến mới.

### 8.2 Poll incremental messages

Sau khi đã có message, frontend dùng cursor:

```http
GET /conversations/{conversationId}/messages?cursorId={lastMessageId}
```

Backend chỉ trả message có `id > cursorId`.

Tại sao dùng cursor?

- Không tải lại toàn bộ message.
- Giảm payload.
- Dễ merge tránh duplicate.

### 8.3 Send message

Request:

```http
POST /conversations/{conversationId}/messages
Authorization: Bearer dev-token-user-5
Content-Type: application/json

{
  "senderId": 5,
  "text": "hello",
  "clientMsgId": "web-..."
}
```

Backend flow:

```text
MessageController.send
  -> auth check senderId == token user
  -> MessageService.send
      -> check sender là active member
      -> validate text not blank
      -> tạo Message
      -> set contentJson = {"text":"..."}
      -> set seq = maxSeq + 1
      -> save message
      -> update conversation.lastMessageAt
      -> broadcast WS topic conversation
      -> broadcast WS topic user list cho member khác sender
      -> return saved
```

### 8.4 Mark read

Khi user mở chat, frontend gọi:

```http
POST /conversations/{conversationId}/messages/mark-read
Authorization: Bearer dev-token-user-5
Content-Type: application/json

{
  "userId": 5
}
```

Backend flow:

```text
MessageController.markRead
  -> auth check userId == token user
  -> MessageService.markRead
      -> check active member
      -> lấy message mới nhất conversation
      -> upsert ConversationReadState
      -> set lastReadMessageId = newest message id
      -> set lastReadSeq = newest seq
```

Frontend sau mark-read success set `unreadCount=0` cho conversation.

---

## 9. WebSocket flow

### 9.1 Endpoint

```text
/ws
```

FE tạo STOMP client:

```ts
const client = new Client({
  brokerURL: `${base}/ws`,
  reconnectDelay: 0,
  connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
})
```

### 9.2 Topic message trong chat đang mở

```text
/topic/conversations/{conversationId}
```

Ứng dụng:

- Khi đang mở chat, nhận tin mới tức thì.
- Append vào `messages`.
- Scroll xuống cuối.
- Nếu incoming từ người khác và tab visible -> mark-read.

### 9.3 Topic unread/list theo user

```text
/topic/users/{userId}/conversations
```

Ứng dụng:

- Khi user chưa mở conversation, vẫn nhận event message.
- Tăng badge đỏ.
- Highlight conversation row.
- Sort conversation lên đầu.
- Sau đó throttle gọi `loadConversations()` để đồng bộ source-of-truth từ backend.

### 9.4 WS auth guard

`WsAuthHandshakeInterceptor` và `WsSubscriptionAuthInterceptor` đảm bảo:

- CONNECT có auth user.
- Subscribe `/topic/conversations/{id}` phải là active member của conversation.
- Subscribe `/topic/users/{userId}/conversations` chỉ được subscribe topic của chính mình.

Tại sao cần guard?

Không guard thì user A có thể tự subscribe topic conversation của user B để đọc lén event.

---

## 10. Frontend state model

Trong `App.tsx`, state chính:

```ts
conversations       // list đoạn chat
users               // list user để tạo DIRECT/GROUP
selectedUsers       // user đang chọn trong popup tạo nhóm
selectedId          // conversation đang mở
messages            // messages của selected conversation
text                // input gửi tin
info                // error/info UI
search              // search conversation
filter              // all/unread/groups
groupModalOpen      // bật/tắt popup tạo nhóm
groupTitle          // tên nhóm
groupSearch         // search user trong popup
wsState             // connected/connecting/fallback
```

### LocalStorage keys

```text
chat_user
chat_token
chat_selected_conversation_id
chat_direct_names
```

Ứng dụng:

- Giữ đăng nhập sau refresh.
- Restore conversation đang mở.
- Cache tên DIRECT peer để refresh không bị hiện `DIRECT #id` lâu.

---

## 11. UI flow chi tiết

### 11.1 Login/Register

```text
User nhập username/password
  -> submit AuthScreen
  -> POST /auth/login hoặc /auth/register
  -> nếu success:
       save chat_user
       save chat_token
       render ChatScreen
  -> nếu fail:
       show error
```

### 11.2 Click user để chat DIRECT

```text
User click avatar user ở active users strip
  -> createDirect(userId)
  -> POST /conversations type DIRECT
  -> backend trả existing hoặc new conversation
  -> cache displayName peer
  -> setSelectedId(conversation.id)
  -> fetchMessages(conversation.id)
  -> loadConversations()
  -> UI mở chat screen
```

### 11.3 Tạo nhóm bằng popup

```text
Click nút ＋
  -> setGroupModalOpen(true)
  -> popup hiện
  -> user nhập tên nhóm
  -> user search
  -> click user ở list dưới
       -> push vào selectedUsers
       -> user biến mất khỏi list dưới
  -> click chip selected
       -> remove khỏi selectedUsers
  -> click Tạo nhóm
       -> validate selectedUsers >= 2
       -> POST /conversations type GROUP
       -> close modal
       -> loadConversations
       -> open group chat
```

### 11.4 Gửi tin

```text
User nhập text
  -> submit form send
  -> POST /messages
  -> backend save DB
  -> backend WS broadcast
  -> FE sender fetch incremental + loadConversations
  -> FE recipients nhận WS event
```

### 11.5 Nhận tin khi đang mở chat

```text
WS /topic/conversations/{selectedId}
  -> JSON parse Message
  -> nếu chưa có id này trong messages
       append + sort
       scroll bottom
  -> nếu sender khác current user
       markRead(selectedId)
       throttle loadConversations
```

### 11.6 Nhận tin khi chưa mở chat

```text
WS /topic/users/{auth.userId}/conversations
  -> JSON parse Message
  -> nếu message.conversationId != selectedId
       tăng unreadCount local
       update lastMessageAt
       sort conversation lên đầu
  -> throttle loadConversations để đồng bộ chính xác
```

### 11.7 Xóa chat khỏi danh sách

```text
Swipe-left conversation row
  -> row trượt sang trái
  -> hiện nút 🗑
Click 🗑
  -> confirm
  -> DELETE /conversations/{id}?userId=currentUser
  -> nếu success:
       remove row khỏi conversations
       nếu đang mở chat đó:
           selectedId=null
           messages=[]
           remove localStorage selected id
```

### 11.8 Mobile flow

```text
Mobile home
  -> list chat + active users + filter
Click conversation
  -> mở full chat screen
Click back arrow
  -> quay lại home/list
```

### 11.9 Auto-scroll cuối chat

Khi mở chat:

```text
selectedId thay đổi
  -> setMessages([])
  -> fetchMessages
  -> requestAnimationFrame scrollTop=scrollHeight
```

Khi có tin mới:

```text
messages.length thay đổi
  -> scrollToBottom(true)
```

---

## 12. API contract

### 12.1 Response wrapper

Tất cả API chính trả:

```json
{
  "success": true,
  "data": {},
  "message": "OK"
}
```

Fail:

```json
{
  "success": false,
  "data": null,
  "message": "..."
}
```

### 12.2 Auth

#### Register

```http
POST /auth/register
```

#### Login

```http
POST /auth/login
```

### 12.3 Users

```http
GET /users?excludeUserId={id}
Authorization: Bearer dev-token-user-{id}
```

### 12.4 Conversations

#### Create

```http
POST /conversations
Authorization: Bearer dev-token-user-{creatorId}
```

Body DIRECT:

```json
{
  "creatorId": 5,
  "type": "DIRECT",
  "title": null,
  "memberIds": [6]
}
```

Body GROUP:

```json
{
  "creatorId": 5,
  "type": "GROUP",
  "title": "Team",
  "memberIds": [6,7]
}
```

#### List

```http
GET /conversations?userId=5&limit=50
Authorization: Bearer dev-token-user-5
```

#### Hide/delete for user

```http
DELETE /conversations/{conversationId}?userId=5
Authorization: Bearer dev-token-user-5
```

#### Direct peer

```http
GET /conversations/{conversationId}/direct-peer?userId=5
Authorization: Bearer dev-token-user-5
```

### 12.5 Messages

#### List

```http
GET /conversations/{conversationId}/messages
Authorization: Bearer dev-token-user-5
```

#### List since cursor

```http
GET /conversations/{conversationId}/messages?cursorId=100
Authorization: Bearer dev-token-user-5
```

#### Send

```http
POST /conversations/{conversationId}/messages
Authorization: Bearer dev-token-user-5
```

```json
{
  "senderId": 5,
  "text": "hello",
  "clientMsgId": "web-171..."
}
```

#### Mark read

```http
POST /conversations/{conversationId}/messages/mark-read
Authorization: Bearer dev-token-user-5
```

```json
{
  "userId": 5
}
```

---

## 13. Error contract

### 13.1 401 Unauthorized

Khi thiếu token hoặc token không parse được.

Ví dụ:

```http
GET /conversations?userId=5
```

Không có Authorization -> `401`.

### 13.2 403 Forbidden

Khi token user không khớp query/body.

Ví dụ:

```http
Authorization: Bearer dev-token-user-5
GET /conversations?userId=6
```

-> `403`.

### 13.3 400 Bad Request

Validation fail:

- text blank.
- DIRECT không đúng 1 member.
- GROUP ít hơn 2 member.

### 13.4 500 Internal Server Error

Bug không mong muốn. Không nên dùng 500 cho validation/auth.

---

## 14. Test strategy

### 14.1 Frontend build

```bash
cd frontend
npm run build
```

Kiểm tra TypeScript + Vite build.

### 14.2 Backend tests

```bash
cd backend
./mvnw -q test
```

Hiện có unit tests cho WS subscription interceptor.

### 14.3 Smoke core MVP

```bash
./smoke.sh
```

Flow:

1. Login users.
2. Create direct both directions.
3. Send message.
4. Verify unread > 0.
5. Mark read.
6. Verify unread = 0.
7. Direct peer endpoint.

### 14.4 Negative auth smoke

```bash
./smoke-auth-negative.sh
```

Flow:

1. No token -> 401.
2. Token A + userId B -> 403.
3. Token A + senderId B -> 403.
4. WS subscribe non-member -> fail.

### 14.5 WS latency smoke

```bash
P95_MAX_MS=1000 MODE=ws ./ws-latency-smoke.sh
```

Dùng để kiểm tra WS nhận được message và latency.

---

## 15. Vận hành / Runbook

### 15.1 Restart backend an toàn

```bash
cd /opt/raize/chat-realtime/backend
./mvnw -q -DskipTests package
lsof -i :8080 -t | xargs -r kill -9
nohup java -Xms128m -Xmx384m -jar target/chat-realtime-0.0.1-SNAPSHOT.jar > /tmp/chat-realtime-backend.log 2>&1 &
```

### 15.2 Check backend sống chưa

```bash
lsof -i :8080
```

### 15.3 Xem lỗi backend

```bash
grep -a -n "ERROR\|Exception" /tmp/chat-realtime-backend.log | tail -n 80
```

### 15.4 Check conversation list performance

Backend log có dòng:

```text
conversations.list userId=<id> limit=<n> count=<n> tookMs=<ms>
```

Có thể xem nhanh:

```bash
grep -a "conversations.list" /tmp/chat-realtime-backend.log | tail -n 50
```

### 15.5 Frontend dev server

```bash
cd /opt/raize/chat-realtime/frontend
npm run dev -- --host 0.0.0.0 --port 5173
```

---

## 16. Cách mở rộng đúng hướng

### 16.1 Tách frontend

Nên tách:

```text
src/
├── api/
│   ├── authApi.ts
│   ├── conversationApi.ts
│   └── messageApi.ts
├── components/
│   ├── AuthScreen.tsx
│   ├── ChatScreen.tsx
│   ├── ConversationList.tsx
│   ├── GroupCreateModal.tsx
│   └── MessageList.tsx
├── hooks/
│   ├── useChatWs.ts
│   ├── useConversations.ts
│   └── useMessages.ts
└── types.ts
```

Lý do:

- `App.tsx` hiện quá dài.
- Tách giúp test dễ hơn.
- WS/polling logic nên nằm trong hook riêng.

### 16.2 Nâng auth production

Hiện token `dev-token-user-{id}` chỉ phù hợp dev.

Production nên đổi sang:

- JWT signed.
- Password hashing BCrypt.
- Refresh token.
- Role/permission rõ ràng.

### 16.3 Message content nâng cấp

Hiện `contentJson` chỉ text.

Có thể mở rộng:

```json
{
  "text": "hello",
  "attachments": [],
  "mentions": []
}
```

### 16.4 Pagination message

Hiện lấy top 50 mới nhất.

Nên thêm:

```http
GET /messages?beforeId=...
```

để scroll lên load lịch sử cũ.

### 16.5 Production WebSocket

Simple broker hiện đủ MVP.

Nếu scale nhiều instance:

- dùng external broker: RabbitMQ/STOMP hoặc Redis pub/sub.
- sticky session hoặc shared broker.

---

## 17. Các câu hỏi “tại sao” quan trọng

### Tại sao DIRECT cần idempotent?

Vì chat 1-1 giữa A và B chỉ nên có một conversation. Nếu không, mỗi lần click user sẽ tạo một chat mới, gây rối dữ liệu.

### Tại sao unread tính ở backend?

Vì frontend không đáng tin và dễ lệch state khi refresh/multi-device. Backend là source of truth.

### Tại sao xóa chat chỉ set inactive member?

Vì conversation có thể có nhiều user. Nếu xóa hard, user khác mất dữ liệu.

### Tại sao WS vẫn cần polling?

Vì WS không đảm bảo luôn kết nối. Polling fallback giúp app không “chết realtime”.

### Tại sao dùng topic user riêng cho unread?

Nếu chỉ subscribe conversation đang mở, user sẽ không nhận event cho conversation khác. Topic user giải quyết unread/list realtime.

### Tại sao mark-read khi mở chat?

Vì hành vi Messenger-like: khi user mở conversation, xem như đã đọc đến tin cuối hiện có.

### Tại sao store UTC, render +7?

DB nên lưu thời gian trung lập UTC. FE render theo timezone người dùng (`Asia/Ho_Chi_Minh`) để tránh lệch khi deploy nhiều vùng.

---

## 18. Checklist cho dev mới

1. Đọc `docs/PROJECT_A_Z.md`.
2. Chạy backend jar mode.
3. Chạy frontend dev server.
4. Register 2 user.
5. Test DIRECT.
6. Test GROUP popup.
7. Test WS badge unread.
8. Run `npm run build`.
9. Run `./mvnw -q test`.
10. Run smoke scripts.

---

## 19. Current known caveats

- Auth hiện là dev-token, chưa production-grade.
- `App.tsx` đang lớn, nên refactor sau khi MVP ổn.
- WS latency strict p95 có thể fail trong dev VPS; script vẫn hữu ích để smoke.
- `spring-boot:run` từng bị kill `137`; dùng jar mode.
- Message history hiện chỉ load 50 message mới nhất.

---

## 20. Tài liệu liên quan

- `docs/FLOW_GUIDE.md`: flow nghiệp vụ chi tiết dạng bước.
- `docs/CODE_COMMENTARY.md`: giải thích code theo từng file.
- `docs/SETUP_RUNBOOK.md`: setup + vận hành + lệnh test.
- `docs/API_CONTRACT.md`: API contract.
- `docs/WS_REALTIME.md`: realtime WS chi tiết.
