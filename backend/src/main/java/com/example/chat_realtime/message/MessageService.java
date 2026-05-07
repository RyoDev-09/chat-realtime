package com.example.chat_realtime.message;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.example.chat_realtime.conversation.ConversationMemberRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessageService {
    private final MessageRepository messageRepository;
    private final ConversationMemberRepository memberRepository;

    public Message send(Long conversationId, Long senderId, String text, String clientMsgId) {
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

        // FIX: messages.seq is NOT NULL -> phải set seq trước khi save
        Long maxSeq = messageRepository.getMaxSeqByConversationId(conversationId);
        m.setSeq((maxSeq == null ? 0L : maxSeq) + 1L);

        return messageRepository.save(m);
    }

    public List<Message> list(Long conversationId) {
        return messageRepository.findTop50ByConversationIdOrderByIdDesc(conversationId).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }
}
