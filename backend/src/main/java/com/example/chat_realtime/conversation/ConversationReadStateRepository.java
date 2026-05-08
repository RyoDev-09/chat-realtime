package com.example.chat_realtime.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationReadStateRepository extends JpaRepository<ConversationReadState, Long> {
    Optional<ConversationReadState> findByConversationIdAndUserId(Long conversationId, Long userId);
}
