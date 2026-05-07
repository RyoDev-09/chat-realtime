package com.example.chat_realtime.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findTop50ByConversationIdOrderByIdDesc(Long conversationId);

    @Query("select coalesce(max(m.seq), 0) from Message m where m.conversationId = :conversationId")
    Long getMaxSeqByConversationId(@Param("conversationId") Long conversationId);
}
