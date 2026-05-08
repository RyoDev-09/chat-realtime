import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Client } from '@stomp/stompjs'
import './App.css'

type ApiResp<T> = { success: boolean; message: string; data: T }
type AuthData = { token: string; userId: number; username: string; displayName: string }
type Conversation = { id: number; type: 'DIRECT' | 'GROUP'; title: string; lastMessageAt?: string; unreadCount?: number; isOnline?: boolean; isFavorite?: boolean }
type Message = { id: number; conversationId: number; senderId: number; contentJson: string; createdAt: string }
type ChatRow = { kind:'separator'; id:string; label:string } | { kind:'message'; message:Message; mine:boolean; text:string; showAvatar:boolean; compact:boolean }
type UserListItem = { id: number; username: string; displayName: string }

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
const authHeaders = (): Record<string,string> => { const t = localStorage.getItem('chat_token'); const h:Record<string,string> = { 'Content-Type':'application/json' }; if (t) h['Authorization'] = `Bearer ${t}`; return h }

export default function App() {
  const [auth,setAuth]=useState<AuthData|null>(()=>{const raw=localStorage.getItem('chat_user');return raw?JSON.parse(raw):null})
  if(!auth) return <AuthScreen onLogin={setAuth}/>
  return <ChatScreen auth={auth} onLogout={()=>{localStorage.clear();setAuth(null)}}/>
}

function AuthScreen({ onLogin }: { onLogin: (a: AuthData) => void }) {
  const [mode,setMode]=useState<'login'|'register'>('login'); const [username,setUsername]=useState(''); const [displayName,setDisplayName]=useState(''); const [password,setPassword]=useState(''); const [msg,setMsg]=useState('')
  const submit=async(e:FormEvent)=>{e.preventDefault();setMsg('');const url=mode==='login'?`${API_BASE}/auth/login`:`${API_BASE}/auth/register`;const body=mode==='login'?{username,password}:{username,displayName,password};try{const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const j:ApiResp<AuthData>=await r.json();if(!j.success)return setMsg(j.message);localStorage.setItem('chat_user',JSON.stringify(j.data));localStorage.setItem('chat_token',j.data.token);onLogin(j.data)}catch{setMsg('Network error')}}
  return <div className='wrap'><div className='card'><h2>Chat Realtime</h2><div className='tabs'><button className={mode==='login'?'active':''} onClick={()=>setMode('login')}>Login</button><button className={mode==='register'?'active':''} onClick={()=>setMode('register')}>Register</button></div><form onSubmit={submit}><input placeholder='username' value={username} onChange={e=>setUsername(e.target.value)} required/>{mode==='register'&&<input placeholder='display name' value={displayName} onChange={e=>setDisplayName(e.target.value)}/>}<input placeholder='password' type='password' value={password} onChange={e=>setPassword(e.target.value)} required/><button type='submit'>{mode==='login'?'Login':'Create account'}</button></form>{msg&&<p className='err'>{msg}</p>}</div></div>
}

function ChatScreen({ auth, onLogout }: { auth: AuthData; onLogout: ()=>void }) {
  const [conversations,setConversations]=useState<Conversation[]>([]); const [users,setUsers]=useState<UserListItem[]>([]); const [selectedUsers,setSelectedUsers]=useState<number[]>([]); const [selectedId,setSelectedId]=useState<number|null>(()=>{const raw=localStorage.getItem('chat_selected_conversation_id');return raw?Number(raw):null}); const [messages,setMessages]=useState<Message[]>([]); const [text,setText]=useState(''); const [info,setInfo]=useState(''); const [groupTitle,setGroupTitle]=useState(''); const [groupSearch,setGroupSearch]=useState(''); const [groupModalOpen,setGroupModalOpen]=useState(false); const [usersError,setUsersError]=useState(''); const [usersLoading,setUsersLoading]=useState(false); const [search,setSearch]=useState(''); const [filter,setFilter]=useState<'all'|'unread'|'groups'>('all')
  const [pollMs,setPollMs]=useState(2500)
  const [wsState,setWsState]=useState<'connected'|'connecting'|'fallback'>('connecting')
  const wsRetryRef = useRef(0)
  const convoReloadTsRef = useRef(0)
  const [directNameByConversationId,setDirectNameByConversationId]=useState<Record<number,string>>(()=>{try{return JSON.parse(localStorage.getItem('chat_direct_names')||'{}')}catch{return {}}})
  const [mobileSidebarOpen,setMobileSidebarOpen]=useState(false)
  const [activeDirectUserId,setActiveDirectUserId]=useState<number|null>(null)
  const [swipedConversationId,setSwipedConversationId]=useState<number|null>(null)
  const selected = useMemo(()=>conversations.find(c=>c.id===selectedId)||null,[conversations,selectedId])
  const fetchingRef = useRef(false)
  const wsRef = useRef<Client|null>(null)
  const msgsRef = useRef<HTMLDivElement|null>(null)
  const selectedIdRef = useRef<number|null>(selectedId)
  const scrollToBottom = (smooth=false) => requestAnimationFrame(()=>msgsRef.current?.scrollTo({top:msgsRef.current.scrollHeight,behavior:smooth?'smooth':'auto'}))

  const loadUsers=async()=>{setUsersLoading(true);setUsersError('');try{const r=await fetch(`${API_BASE}/users?excludeUserId=${auth.userId}`,{headers:authHeaders()});const j:ApiResp<UserListItem[]>=await r.json();if(!j.success){setUsersError(j.message||'Không tải được danh sách users');setUsers([]);return;}setUsers(j.data||[]);}catch{setUsersError('Không kết nối được backend để tải users');setUsers([]);}finally{setUsersLoading(false)}}
  // Conversation list is the UI source for unread badges, highlight state and ordering.
  // Backend remains the source of truth, so this is also called periodically as WS fallback.
  const loadConversations=async()=>{const r=await fetch(`${API_BASE}/conversations?userId=${auth.userId}&limit=50`,{headers:authHeaders()});const j:ApiResp<Conversation[]>=await r.json();if(j.success){const list=(j.data||[]).sort((a,b)=>new Date(b.lastMessageAt||0).getTime()-new Date(a.lastMessageAt||0).getTime());setConversations(list)}}

  const markRead = async (conversationId:number) => {
    // Opening a conversation means the user has seen the latest messages.
    // Only clear the local unread badge after the backend confirms mark-read.
    try {
      const r = await fetch(`${API_BASE}/conversations/${conversationId}/messages/mark-read`,{method:'POST',headers:authHeaders(),body:JSON.stringify({userId:auth.userId})})
      const j:ApiResp<boolean> = await r.json()
      if (j.success) {
        setConversations(prev=>prev.map(c=>c.id===conversationId?{...c,unreadCount:0}:c))
      } else {
        setInfo('Không thể cập nhật trạng thái đã đọc')
      }
    } catch {
      setInfo('Không thể cập nhật trạng thái đã đọc')
    }
  }

  const resolveDirectNames = async (list: Conversation[]) => {
    // DIRECT conversations intentionally have no title. Resolve the peer display name lazily
    // and cache it in localStorage so refresh does not briefly show a raw DIRECT id.
    const directs = list.filter(c => c.type==='DIRECT')
    for (const c of directs) {
      if (directNameByConversationId[c.id]) continue
      try {
        const r = await fetch(`${API_BASE}/conversations/${c.id}/direct-peer?userId=${auth.userId}`,{headers:authHeaders()})
        const j: ApiResp<{id:number;username:string;displayName:string}> = await r.json()
        if (j.success && j.data?.displayName) {
          setDirectNameByConversationId(prev => ({...prev, [c.id]: j.data.displayName}))
          if (selectedId === c.id) setActiveDirectUserId(j.data.id)
        }
      } catch {}
    }
  }

  // Incremental message fetch. cursorId keeps polling cheap: only messages newer than
  // the last rendered id are requested, then merged by id to avoid duplicates from WS+polling.
  const fetchMessages=async(cid:number,cursorId?:number)=>{if(fetchingRef.current) return; fetchingRef.current=true; try{const url=cursorId?`${API_BASE}/conversations/${cid}/messages?cursorId=${cursorId}`:`${API_BASE}/conversations/${cid}/messages`;const r=await fetch(url,{headers:authHeaders()});if(!r.ok) throw new Error('fetch messages failed');const j:ApiResp<Message[]>=await r.json();if(j.success&&j.data?.length){setMessages(prev=>{const m=new Map(prev.map(x=>[x.id,x]));j.data.forEach(x=>m.set(x.id,x));return [...m.values()].sort((a,b)=>a.id-b.id)});}setPollMs(2500)}catch{setPollMs(v=>Math.min(v*2,10000))}finally{fetchingRef.current=false}}

  useEffect(()=>{loadUsers();loadConversations()},[])
  useEffect(()=>{if(conversations.length) resolveDirectNames(conversations)},[conversations])
  useEffect(()=>{selectedIdRef.current=selectedId;if(selectedId) localStorage.setItem('chat_selected_conversation_id', String(selectedId)); else localStorage.removeItem('chat_selected_conversation_id')},[selectedId])
  useEffect(()=>{localStorage.setItem('chat_direct_names', JSON.stringify(directNameByConversationId||{}))},[directNameByConversationId])
  useEffect(()=>{if(selectedId){setMessages([]);fetchMessages(selectedId).then(()=>scrollToBottom(false));markRead(selectedId)}},[selectedId])
  useEffect(()=>{if(selectedId&&messages.length)scrollToBottom(true)},[selectedId,messages.length])
  useEffect(()=>{const onVisible=()=>{if(document.visibilityState==='visible'&&selectedId){const last=messages[messages.length-1]?.id;fetchMessages(selectedId,last)}};document.addEventListener('visibilitychange',onVisible);return()=>document.removeEventListener('visibilitychange',onVisible)},[selectedId,messages])
  useEffect(()=>{if(!selectedId)return;const effectivePollMs = wsState==='connected' ? 5000 : pollMs;const t=setInterval(()=>{if(document.visibilityState!=='visible')return;const last=messages[messages.length-1]?.id;fetchMessages(selectedId,last)},effectivePollMs);return()=>clearInterval(t)},[selectedId,messages,pollMs,wsState])
  useEffect(()=>{const t=setInterval(()=>{if(document.visibilityState!=='visible')return;loadConversations()},5000);return()=>clearInterval(t)},[])

  useEffect(()=>{
    // WebSocket lifecycle. Recreate client when selectedId changes so the active
    // conversation subscription follows the opened chat; user-list topic stays global.
    const base = API_BASE.replace(/^http/, 'ws')
    const token = localStorage.getItem('chat_token')
    setWsState('connecting')
    const client = new Client({
      brokerURL: `${base}/ws`,
      reconnectDelay: 0,
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    })

    client.onConnect = () => {
      wsRetryRef.current = 0
      setWsState('connected')
      client.subscribe(`/topic/users/${auth.userId}/conversations`, (msg) => {
        // Realtime unread/list flow: fires even when the incoming conversation is not open.
        // Local update is instant; throttled loadConversations reconciles DB truth.
        try {
          const incoming: Message = JSON.parse(msg.body)
          if(incoming.conversationId !== selectedIdRef.current) {
            setConversations(prev=>prev.map(c=>c.id===incoming.conversationId?{...c,unreadCount:(c.unreadCount||0)+1,lastMessageAt:incoming.createdAt||new Date().toISOString()}:c).sort((a,b)=>new Date(b.lastMessageAt||0).getTime()-new Date(a.lastMessageAt||0).getTime()))
          }
          const now = Date.now()
          if (now - convoReloadTsRef.current > 1200) {
            convoReloadTsRef.current = now
            loadConversations()
          }
        } catch {}
      })
      if(selectedId) client.subscribe(`/topic/conversations/${selectedId}`, (msg) => {
        // Realtime message flow for the opened conversation.
        // Duplicate guard is required because polling fallback can fetch the same message.
        try {
          const incoming: Message = JSON.parse(msg.body)
          // Guard against stale subscriptions/list-topic leakage: never render a message
          // unless it belongs to the conversation currently open on screen.
          if (incoming.conversationId !== selectedIdRef.current) return
          setMessages(prev=>{
            if(prev.some(x=>x.id===incoming.id)) return prev
            const next = [...prev, incoming].sort((a,b)=>a.id-b.id)
            setTimeout(()=>scrollToBottom(true),0)
            return next
          })
          if (incoming.senderId !== auth.userId) {
            if (document.visibilityState==='visible' && selectedIdRef.current) markRead(selectedIdRef.current)
            const now = Date.now()
            if (now - convoReloadTsRef.current > 1200) {
              convoReloadTsRef.current = now
              loadConversations()
            }
          }
        } catch {}
      })
    }
    const scheduleReconnect = () => {
      // Custom exponential backoff keeps reconnect attempts controlled and exposes fallback state.
      wsRetryRef.current += 1
      setWsState('fallback')
      const baseDelay = Math.min(30000, 1000 * Math.pow(2, Math.min(wsRetryRef.current, 5)))
      const jitter = Math.floor(Math.random() * 700)
      setTimeout(() => {
        setWsState('connecting')
        try { client.activate() } catch {}
      }, baseDelay + jitter)
    }

    client.onWebSocketClose = () => scheduleReconnect()
    client.onStompError = () => scheduleReconnect()
    client.onWebSocketError = () => scheduleReconnect()
    client.activate()
    wsRef.current = client

    return ()=>{
      try { wsRef.current?.deactivate() } catch {}
      wsRef.current = null
      setWsState('fallback')
    }
  },[selectedId,auth.userId])

  const toggleUser=(id:number)=>setSelectedUsers(prev=>prev.includes(id)?prev.filter(x=>x!==id):[...prev,id])
  void toggleUser
  // Click user avatar/card -> open DIRECT. Backend idempotency guarantees this returns the
  // existing A-B conversation instead of creating duplicates.
  const createDirect=async(uid:number)=>{setInfo('');const r=await fetch(`${API_BASE}/conversations`,{method:'POST',headers:authHeaders(),body:JSON.stringify({creatorId:auth.userId,type:'DIRECT',title:null,memberIds:[uid]})});const j:ApiResp<Conversation>=await r.json();if(!j.success)return setInfo(j.message||'Không mở được chat DIRECT');if(!j.data?.id)return setInfo('Backend không trả conversation id'); const dn=users.find(u=>u.id===uid)?.displayName||`User #${uid}`; setDirectNameByConversationId(prev=>({...prev,[j.data.id]:dn})); setActiveDirectUserId(uid); setSelectedId(j.data.id); localStorage.setItem('chat_selected_conversation_id', String(j.data.id)); setMobileSidebarOpen(false); setMessages([]); await fetchMessages(j.data.id); await loadConversations();}
  // Group popup flow: selectedUsers is built by clicking users in the modal.
  // Backend enforces at least 2 members excluding creator.
  const createGroup=async()=>{if(selectedUsers.length<2)return setInfo('Group cần chọn ít nhất 2 user');const r=await fetch(`${API_BASE}/conversations`,{method:'POST',headers:authHeaders(),body:JSON.stringify({creatorId:auth.userId,type:'GROUP',title:groupTitle||'New Group',memberIds:selectedUsers})});const j:ApiResp<Conversation>=await r.json();if(!j.success)return setInfo(j.message);setSelectedUsers([]);setGroupTitle('');setGroupSearch('');setGroupModalOpen(false);setActiveDirectUserId(null);await loadConversations();if(j.data?.id)setSelectedId(j.data.id)}
  const send=async(e:FormEvent)=>{e.preventDefault();if(!selectedId||!text.trim())return;const r=await fetch(`${API_BASE}/conversations/${selectedId}/messages`,{method:'POST',headers:authHeaders(),body:JSON.stringify({senderId:auth.userId,text,clientMsgId:`web-${Date.now()}`})});const j=await r.json();if(!j.success)return setInfo(j.message||'send failed');setText('');await fetchMessages(selectedId,messages[messages.length-1]?.id);await loadConversations()}
  // Swipe-left delete is a per-user hide, not a global delete.
  // If the opened chat is hidden, clear selected state so the UI returns to the conversation list.
  const deleteConversation=async(cid:number)=>{if(!confirm('Xóa đoạn chat này khỏi danh sách của bạn?'))return;const r=await fetch(`${API_BASE}/conversations/${cid}?userId=${auth.userId}`,{method:'DELETE',headers:authHeaders()});const j:ApiResp<boolean>=await r.json();if(!j.success)return setInfo(j.message||'Không xóa được đoạn chat');setConversations(prev=>prev.filter(c=>c.id!==cid));if(selectedId===cid){setSelectedId(null);setMessages([]);localStorage.removeItem('chat_selected_conversation_id')}setSwipedConversationId(null)}

  const displayTitle = (c?: Conversation|null) => c ? (c.type==='DIRECT' ? (directNameByConversationId[c.id] || `DIRECT #${c.id}`) : (c.title || `Nhóm #${c.id}`)) : ''
  const filteredConversations = conversations.filter(c => {
    const q = search.toLowerCase()
    const title = displayTitle(c).toLowerCase()
    const match = !q || title.includes(q) || String(c.id).includes(q)
    if (!match) return false
    if (filter==='unread') return (c.unreadCount||0) > 0
    if (filter==='groups') return c.type === 'GROUP'
    return true
  })
  const fmtVNTime = (isoLike:string) => {
    const d = new Date(isoLike.endsWith('Z') ? isoLike : `${isoLike}Z`)
    return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Ho_Chi_Minh' })
  }
  const fmtSeparator = (isoLike:string) => {
    const d = new Date(isoLike.endsWith('Z') ? isoLike : `${isoLike}Z`)
    return d.toLocaleString('vi-VN', { day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit', hour12:false, timeZone:'Asia/Ho_Chi_Minh' })
  }
  const minuteKey = (isoLike:string) => Math.floor(new Date(isoLike.endsWith('Z') ? isoLike : `${isoLike}Z`).getTime()/60000)
  // Render model for Messenger-like message grouping:
  // - insert time separator when gap >= 5 minutes,
  // - compact bubbles from the same sender within the same minute,
  // - show avatar only on the last bubble of each minute/sender group.
  const chatRows = useMemo<ChatRow[]>(()=>{
    const rows:ChatRow[] = []
    messages.forEach((m,i)=>{
      const prev = messages[i-1]
      const next = messages[i+1]
      const mk = minuteKey(m.createdAt)
      const prevGap = prev ? mk - minuteKey(prev.createdAt) : Infinity
      if (!prev || prevGap >= 5) rows.push({kind:'separator', id:`sep-${m.id}`, label:fmtSeparator(m.createdAt)})
      let t=''; try{t=JSON.parse(m.contentJson||'{}').text||''}catch{t=m.contentJson||''}
      const mine = m.senderId===auth.userId
      const nextSameMinute = !!next && next.senderId===m.senderId && minuteKey(next.createdAt)===mk
      const prevSameMinute = !!prev && prev.senderId===m.senderId && minuteKey(prev.createdAt)===mk
      rows.push({kind:'message', message:m, mine, text:t, showAvatar:!nextSameMinute, compact:prevSameMinute})
    })
    return rows
  },[messages,auth.userId])

  useEffect(()=>{
    if(!selectedId) return
    if(conversations.some(c=>c.id===selectedId)) return
    setSelectedId(null)
    localStorage.removeItem('chat_selected_conversation_id')
  },[conversations,selectedId])

  return <div className={`chat-layout ${selected?'chat-open':'home-open'}`}>
    <header className='topbar'>
      <div className='brand'>RaizeChat <small style={{marginLeft:8,fontSize:12,opacity:.8}}>WS: {wsState}</small></div>
      <div className='top-actions'>
        <button type='button' onClick={onLogout}>Logout</button>
        <button type='button' className='mobile-only' onClick={()=>selectedId?setSelectedId(null):setMobileSidebarOpen(v=>!v)} aria-label={selectedId?'Back to chats':'Toggle sidebar'}>{selectedId?'←':'☰'}</button>
      </div>
    </header>
    <div className='board'>
    {mobileSidebarOpen && <div className='drawer-overlay' onClick={()=>setMobileSidebarOpen(false)} />}
    <aside className={`left ${mobileSidebarOpen?'open':''}`}>
      <div className='chat-list-title'><b>Đoạn Chat</b><div className='title-actions'><button type='button' className='logout-btn' onClick={onLogout}>Logout</button><button type='button' className='add-group-btn' onClick={()=>setGroupModalOpen(true)} title='Tạo nhóm' aria-label='Tạo nhóm'>＋</button></div></div>
      <div className='sticky-search'><input placeholder='Tìm đoạn chat...' value={search} onChange={e=>setSearch(e.target.value)} /></div>
      <div className='active-users-strip' onWheel={(e)=>{if(Math.abs(e.deltaY)>Math.abs(e.deltaX)){e.currentTarget.scrollLeft += e.deltaY}}}>
        {usersLoading && <span className='active-loading'>Đang tải...</span>}
        {!!usersError && <span className='active-loading err'>{usersError}</span>}
        {!usersLoading && !usersError && users.map(u=><button key={u.id} type='button' className={activeDirectUserId===u.id?'story-user active':'story-user'} onClick={()=>createDirect(u.id)}><span className='story-avatar'>{u.displayName?.[0]?.toUpperCase()||'U'}</span><span>{u.displayName}</span></button>)}
      </div>
      <div className='filter-pills'>
        {([{key:'all',label:'Tất Cả'},{key:'unread',label:'Chưa đọc'},{key:'groups',label:'Nhóm'}] as const).map(f => (
          <button key={f.key} className={filter===f.key?'pill active':'pill'} onClick={()=>setFilter(f.key)}>{f.label}</button>
        ))}
      </div>
      <div className='users-panel chat-list-scroll'>
        {filteredConversations.length===0 && <p className='empty-state'>Chưa có đoạn chat phù hợp.</p>}
        {filteredConversations.map(c=><div key={c.id} className={swipedConversationId===c.id?'chat-swipe open':'chat-swipe'}><button type='button' className='delete-chat-btn' onClick={()=>deleteConversation(c.id)}>🗑</button><div className={`${selectedId===c.id?'contact-row clickable active':'contact-row clickable'} ${(c.unreadCount||0)>0?'unread':''}`} draggable onDragStart={(e)=>{e.dataTransfer.setData('text/plain',String(c.id))}} onDragEnd={(e)=>{if(e.clientX < 120)setSwipedConversationId(c.id)}} onTouchStart={(e)=>{e.currentTarget.dataset.sx=String(e.touches[0].clientX)}} onTouchEnd={(e)=>{const sx=Number(e.currentTarget.dataset.sx||0);const dx=e.changedTouches[0].clientX-sx;if(dx < -45)setSwipedConversationId(c.id);else if(dx > 35)setSwipedConversationId(null)}} onClick={()=>{if(swipedConversationId===c.id){setSwipedConversationId(null);return}setSelectedId(c.id);setMobileSidebarOpen(false)}}><div className='contact-left'><div className='avatar'>{displayTitle(c)[0]?.toUpperCase()||'C'}</div><div className='meta'><div className='name'>{displayTitle(c)} {c.type==='DIRECT'&&<span className='dot'/>}</div><div className='desc'>{c.type==='GROUP'?'Nhóm':'Đang hoạt động'} • {c.lastMessageAt?fmtVNTime(c.lastMessageAt):'mới'}</div></div></div>{!!c.unreadCount&&<span className='badge'>{c.unreadCount}</span>}</div></div>)}
      </div>
    </aside>
    {groupModalOpen && <div className='modal-backdrop' onClick={()=>setGroupModalOpen(false)}><div className='group-modal' onClick={e=>e.stopPropagation()}><div className='modal-head'><h3>Tạo nhóm</h3><button type='button' onClick={()=>setGroupModalOpen(false)}>×</button></div><input placeholder='Tên nhóm' value={groupTitle} onChange={e=>setGroupTitle(e.target.value)} /><input placeholder='Search user...' value={groupSearch} onChange={e=>setGroupSearch(e.target.value)} /><div className='picked-users'>{selectedUsers.length===0?<span className='empty-state'>Chưa chọn user</span>:selectedUsers.map(id=>{const u=users.find(x=>x.id===id);return <button key={id} type='button' className='picked-chip' onClick={()=>setSelectedUsers(prev=>prev.filter(x=>x!==id))}>{u?.displayName||`User #${id}`} ×</button>})}</div><div className='modal-user-list'>{users.filter(u=>!selectedUsers.includes(u.id)&&(`${u.displayName} ${u.username}`.toLowerCase().includes(groupSearch.toLowerCase()))).map(u=><button key={u.id} type='button' className='modal-user-row' onClick={()=>setSelectedUsers(prev=>[...prev,u.id])}><span className='avatar'>{u.displayName?.[0]?.toUpperCase()||'U'}</span><span><b>{u.displayName}</b><small>@{u.username}</small></span></button>)}</div><button type='button' className='create-group-submit' onClick={createGroup}>Tạo nhóm</button></div></div>}
    <main className='right'>{!selected ? <div className='welcome'><div><h2>Welcome to RaizeChat ✨</h2><p className='desc'>Chọn một đoạn chat hoặc bấm user đang hoạt động để bắt đầu.</p></div></div> : <><div className='chat-title'><button type='button' className='back-btn mobile-only' onClick={()=>setSelectedId(null)} aria-label='Quay lại'>←</button><div className='avatar'>{displayTitle(selected)[0]?.toUpperCase()||'C'}</div><div><h3 style={{margin:0}}>{selected.type==='DIRECT'?(directNameByConversationId[selected.id]||<span className='name-skeleton' aria-label='loading name' />):displayTitle(selected)}</h3><small className='desc'>{selected.type==='GROUP'?'Các thành viên trong nhóm':'Đang hoạt động'} • WS: {wsState}</small></div></div><div className='msgs' ref={msgsRef}>{chatRows.map(row=>row.kind==='separator'?<div key={row.id} className='time-separator'><span>{row.label}</span></div>:<div key={row.message.id} className={`${row.mine?'msg-wrap me':'msg-wrap'} ${row.compact?'compact':''}`}><div className='mini-avatar'>{row.showAvatar?(row.mine?auth.displayName?.[0]?.toUpperCase():displayTitle(selected)[0]?.toUpperCase()):''}</div><div className='msg-col'><div className={row.mine?'msg out':'msg in'} data-time={fmtVNTime(row.message.createdAt)}>{row.text}</div></div></div>)}</div><form onSubmit={send} className='send'><input value={text} onChange={e=>setText(e.target.value)} placeholder='Nhập tin nhắn...'/><button type='submit'>Gửi</button></form>{info&&<p className='err'>{info}</p>}</>}</main>
  </div></div>
}
