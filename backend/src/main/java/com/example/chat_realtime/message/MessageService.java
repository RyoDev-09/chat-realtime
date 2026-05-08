package com.example.chat_realtime.message;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.example.chat_realtime.conversation.Conversation;
import com.example.chat_realtime.conversation.ConversationMemberRepository;
import com.example.chat_realtime.conversation.ConversationReadState;
import com.example.chat_realtime.conversation.ConversationReadStateRepository;
import com.example.chat_realtime.conversation.ConversationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessageService {
    private final MessageRepository messageRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationReadStateRepository readStateRepository;
    private final ConversationRepository conversationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public Message send(Long conversationId, Long senderId, String text, String clientMsgId) {
        // Send-message business flow:
        // 1) authorize sender membership, 2) persist message with monotonic seq,
        // 3) update conversation ordering timestamp, 4) push realtime events.
        memberRepository.findByConversationIdAndUserIdAndIsActiveTrue(conversationId, senderId)
                .orElseThrow(() -> new RuntimeException("sender is not a member of conversation"));

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }

        Message m = new Message();
        m.setConversationId(conversationId);
        m.setSenderId(senderId);
        m.setClientMsgId((clientMsgId == null || clientMsgId.isBlank())
                ? UUID.randomUUID().toString()
                : clientMsgId);
        m.setContentType("TEXT");
        m.setContentJson("{\"text\":\"" + text.replace("\"", "\\\"") + "\"}");

        // messages.seq is NOT NULL and is used as a stable per-conversation ordering cursor.
        // It is assigned before save because DB insert would fail without it.
        Long maxSeq = messageRepository.getMaxSeqByConversationId(conversationId);
        m.setSeq((maxSeq == null ? 0L : maxSeq) + 1L);

        Message saved = messageRepository.save(m);
        Conversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv != null) {
            conv.setLastMessageAt(saved.getCreatedAt() == null ? LocalDateTime.now() : saved.getCreatedAt());
            conversationRepository.save(conv);
        }
        // Realtime channel #1: selected/open chat receives the full message immediately.
        messagingTemplate.convertAndSend("/topic/conversations/" + conversationId, saved);
        memberRepository.findByConversationIdAndIsActiveTrue(conversationId).forEach(member -> {
            if (!member.getUserId().equals(senderId)) {
                // Realtime channel #2: each recipient's conversation list receives an event
                // even when that conversation is not open. FE uses it for unread badge/highlight.
                messagingTemplate.convertAndSend("/topic/users/" + member.getUserId() + "/conversations", saved);
            }
        });
        return saved;
    }

    public List<Message> list(Long conversationId) {
        return messageRepository.findTop50ByConversationIdOrderByIdDesc(conversationId).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    public List<Message> listSince(Long conversationId, Long cursorId) {
        if (cursorId == null || cursorId <= 0) {
            return list(conversationId);
        }
        return messageRepository.findTop50ByConversationIdAndIdGreaterThanOrderByIdAsc(conversationId, cursorId);
    }

    public void markRead(Long conversationId, Long userId) {
        // Read-state flow: when the user opens a conversation, mark it read up to the newest
        // message currently stored. Conversation list unread count is then recalculated from DB.
        memberRepository.findByConversationIdAndUserIdAndIsActiveTrue(conversationId, userId)
                .orElseThrow(() -> new RuntimeException("user is not a member of conversation"));

        Message last = messageRepository.findTop50ByConversationIdOrderByIdDesc(conversationId).stream().findFirst().orElse(null);
        if (last == null) return;

        ConversationReadState rs = readStateRepository.findByConversationIdAndUserId(conversationId, userId)
                .orElseGet(() -> {
                    ConversationReadState x = new ConversationReadState();
                    x.setConversationId(conversationId);
                    x.setUserId(userId);
                    return x;
                });

        rs.setLastReadMessageId(last.getId());
        rs.setLastReadSeq(last.getSeq() == null ? 0L : last.getSeq());
        readStateRepository.save(rs);
    }
}
