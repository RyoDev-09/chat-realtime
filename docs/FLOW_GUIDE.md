# Chat Realtime — Flow Guide cực chi tiết

Tài liệu này tập trung vào “người dùng thao tác gì -> frontend làm gì -> backend làm gì -> DB/WS thay đổi gì -> bước tiếp theo là gì”.

---

## 1. Flow khởi động app

```text
User mở FE
  ↓
App() đọc localStorage.chat_user
  ↓
Nếu không có auth -> render AuthScreen
Nếu có auth -> render ChatScreen
```

### Vì sao làm vậy?

- Giữ user không phải login lại sau refresh.
- Nhưng logout chỉ cần clear localStorage.

---

## 2. Flow đăng ký

```text
User chọn tab Register
  ↓
Nhập username/displayName/password
  ↓
Submit form
  ↓
FE POST /auth/register
  ↓
BE tạo User
  ↓
BE trả token dev-token-user-{id}
  ↓
FE lưu chat_user + chat_token
  ↓
Render ChatScreen
  ↓
ChatScreen load users + conversations
```

### Điểm cần nhớ

- Token hiện chỉ phục vụ dev.
- Production phải hash password + JWT thật.

---

## 3. Flow đăng nhập

```text
User chọn Login
  ↓
Nhập username/password
  ↓
FE POST /auth/login
  ↓
BE verify user
  ↓
BE trả token + user profile
  ↓
FE lưu localStorage
  ↓
Render ChatScreen
```

Nếu sai thông tin:

```text
BE trả success=false
  ↓
FE setMsg(message)
  ↓
UI hiện lỗi
```

---

## 4. Flow load màn chat ban đầu

Khi `ChatScreen` mount:

```text
useEffect init
  ├── loadUsers()
  └── loadConversations()
```

### loadUsers()

```text
GET /users?excludeUserId=currentUser
  ↓
BE trả danh sách user khác current user
  ↓
FE render active-users-strip
```

### loadConversations()

```text
GET /conversations?userId=currentUser&limit=50
  ↓
BE check token == userId
  ↓
BE lấy active memberships
  ↓
BE batch count unread
  ↓
BE sort by lastMessageAt desc
  ↓
FE setConversations(list)
```

Bước tiếp theo:

```text
conversations thay đổi
  ↓
resolveDirectNames(conversations)
  ↓
DIRECT nào chưa có tên peer thì gọi /direct-peer
```

---

## 5. Flow tạo DIRECT bằng click avatar user

```text
User click avatar B
  ↓
FE createDirect(B.id)
  ↓
POST /conversations
body: { creatorId:A, type:DIRECT, memberIds:[B] }
  ↓
BE check token A == creatorId A
  ↓
ConversationService.validateCreateInput
  ↓
Build directPairKey = min(A,B):max(A,B)
  ↓
Nếu DIRECT đã tồn tại:
     reactivate member A/B nếu từng bị hide
     return existing conversation
Nếu chưa tồn tại:
     create conversation
     create active member A
     create active member B
     return new conversation
  ↓
FE setSelectedId(conversation.id)
  ↓
FE fetchMessages(conversation.id)
  ↓
FE loadConversations()
```

### Tại sao click avatar lại mở chat ngay?

Đây là Messenger-like UX: user không cần qua màn tạo conversation thủ công.

---

## 6. Flow tạo GROUP bằng popup

```text
User click nút ＋
  ↓
setGroupModalOpen(true)
  ↓
Popup hiện
```

Trong popup:

```text
User nhập tên nhóm
  ↓
User search user
  ↓
List dưới filter theo groupSearch
  ↓
Click một user
  ↓
setSelectedUsers([...prev, user.id])
  ↓
User đó biến khỏi list dưới vì filter !selectedUsers.includes(id)
  ↓
User đó xuất hiện ở picked-users
```

Khi bấm tạo nhóm:

```text
createGroup()
  ↓
Nếu selectedUsers.length < 2 -> báo lỗi
  ↓
POST /conversations type GROUP
  ↓
BE validate GROUP >= 2 member ngoài creator
  ↓
BE tạo conversation + active members
  ↓
FE clear selectedUsers/groupTitle/groupSearch
  ↓
FE close modal
  ↓
FE loadConversations()
  ↓
FE setSelectedId(group.id)
```

---

## 7. Flow mở conversation

```text
User click conversation row
  ↓
setSelectedId(c.id)
  ↓
Mobile: setMobileSidebarOpen(false)
```

React effect chạy:

```text
selectedId thay đổi
  ↓
setMessages([])
  ↓
fetchMessages(selectedId)
  ↓
markRead(selectedId)
  ↓
scrollToBottom(false)
```

Sau khi messages render:

```text
messages.length thay đổi
  ↓
scrollToBottom(true)
```

### Vì sao mark-read khi mở?

Vì user đã vào xem conversation, nên unread count nên reset.

---

## 8. Flow gửi tin nhắn

```text
User nhập text
  ↓
Submit form
  ↓
send(e)
  ↓
POST /conversations/{selectedId}/messages
body: { senderId, text, clientMsgId }
  ↓
BE auth check senderId == token user
  ↓
BE check sender là active member
  ↓
BE validate text not blank
  ↓
BE set seq = maxSeq + 1
  ↓
BE save message
  ↓
BE update conversation.lastMessageAt
  ↓
BE broadcast WS conversation topic
  ↓
BE broadcast WS user-list topic cho các member khác sender
  ↓
FE sender clear text
  ↓
FE sender fetchMessages(cursor)
  ↓
FE sender loadConversations()
```

---

## 9. Flow nhận tin realtime khi đang mở chat

```text
BE broadcast /topic/conversations/{id}
  ↓
FE selected conversation subscriber nhận msg
  ↓
JSON.parse(msg.body)
  ↓
Nếu message id đã tồn tại -> bỏ qua để tránh duplicate
  ↓
Append message + sort by id
  ↓
scrollToBottom(true)
  ↓
Nếu incoming.senderId != currentUser:
     nếu tab visible -> markRead(selectedId)
     throttle loadConversations()
```

### Tại sao vẫn check duplicate?

Vì message có thể tới từ cả WS và polling. Duplicate check bằng `id` giúp UI không render trùng.

---

## 10. Flow nhận unread realtime khi chưa mở chat

```text
BE broadcast /topic/users/{recipientId}/conversations
  ↓
FE user topic subscriber nhận msg
  ↓
Nếu incoming.conversationId != selectedId:
     tăng unreadCount local
     update lastMessageAt local
     sort conversation lên đầu
  ↓
throttle loadConversations()
```

### Vì sao cần topic user?

Nếu user chỉ subscribe chat đang mở thì sẽ không nhận event cho conversation khác. Topic user giúp danh sách chat realtime.

---

## 11. Flow polling fallback

### Message polling

```text
Nếu selectedId tồn tại
  ↓
setInterval
  ↓
Nếu wsState connected -> poll mỗi 5s
Nếu fallback -> poll theo pollMs, mặc định 2.5s
  ↓
GET messages?cursorId=lastMessageId
  ↓
Merge vào messages
```

### Conversation polling

```text
Mỗi 5s nếu tab visible
  ↓
loadConversations()
```

### Vì sao vẫn polling khi WS connected?

- Chống miss event.
- Chống reconnect edge case.
- Giữ dữ liệu đúng theo backend source of truth.

---

## 12. Flow unread/read

### Khi có tin mới từ A đến B

```text
A send message
  ↓
DB insert message senderId=A
  ↓
B chưa mark-read
  ↓
GET /conversations của B tính unreadCount > 0
  ↓
FE B hiển thị badge đỏ + highlight
```

### Khi B mở chat

```text
B click conversation
  ↓
FE markRead
  ↓
BE set lastReadMessageId = newest message id
  ↓
GET /conversations lần sau unreadCount = 0
  ↓
FE xóa badge/highlight
```

---

## 13. Flow xóa chat khỏi danh sách

```text
User swipe-left row
  ↓
CSS transform translateX(-72px)
  ↓
Nút 🗑 hiện
  ↓
Click delete
  ↓
confirm()
  ↓
DELETE /conversations/{id}?userId=currentUser
  ↓
BE check token == userId
  ↓
BE find active member
  ↓
BE set isActive=false
  ↓
FE remove row local
  ↓
Nếu đang mở chat đó:
      selectedId=null
      messages=[]
      remove localStorage selected id
```

---

## 14. Flow logout

```text
User click Logout
  ↓
localStorage.clear()
  ↓
setAuth(null)
  ↓
App render AuthScreen
```

---

## 15. Flow mobile

```text
Mobile home-open
  ↓
Hiện topbar + left/list
  ↓
Click conversation
  ↓
chat-open
  ↓
Hiện right/chat full screen
  ↓
Click back arrow
  ↓
setSelectedId(null)
  ↓
Quay về home/list
```

---

## 16. Flow search/filter conversation

```text
User nhập search
  ↓
filteredConversations filter theo displayTitle hoặc id
```

Filter:

```text
Tất Cả -> tất cả conversation match search
Chưa đọc -> unreadCount > 0
Nhóm -> type === GROUP
```

---

## 17. Flow direct name resolve

DIRECT conversation không có title. FE cần hiển thị tên peer.

```text
conversations loaded
  ↓
resolveDirectNames
  ↓
For each DIRECT chưa cache name:
      GET /conversations/{id}/direct-peer?userId=currentUser
  ↓
BE tìm member khác current user
  ↓
BE trả displayName
  ↓
FE cache vào localStorage.chat_direct_names
```

---

## 18. Debug flow theo triệu chứng

### Conversation không hiện

Check:

1. User có active member không?
2. `GET /conversations?userId=...` trả gì?
3. Token có đúng user không?
4. Có bị `is_active=false` do xóa không?

### Unread không hiện

Check:

1. Message sender có khác current user không?
2. `conversation_read_state` đang đọc tới id nào?
3. `GET /conversations` có `unreadCount` không?
4. WS user topic có connected không?

### WS không connected

Check:

1. FE badge `WS: connected/fallback`.
2. Backend log có reject không.
3. Token có gửi qua STOMP connect không.
4. User có subscribe đúng topic của mình không.

### Xóa chat 500

Check:

1. Backend có restart code mới chưa?
2. Endpoint DELETE tồn tại chưa?
3. Token/userId mismatch không?
4. Log `/tmp/chat-realtime-backend.log`.
