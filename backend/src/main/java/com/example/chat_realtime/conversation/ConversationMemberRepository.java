package com.example.chat_realtime.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, Long> {
    List<ConversationMember> findByUserIdAndIsActiveTrue(Long userId);

    Optional<ConversationMember> findByConversationIdAndUserIdAndIsActiveTrue(Long conversationId, Long userId);

    Optional<ConversationMember> findByConversationIdAndUserId(Long conversationId, Long userId);

    List<ConversationMember> findByConversationIdAndIsActiveTrue(Long conversationId);
}
