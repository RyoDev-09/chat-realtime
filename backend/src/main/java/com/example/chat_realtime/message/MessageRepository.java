package com.example.chat_realtime.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findTop50ByConversationIdOrderByIdDesc(Long conversationId);

    List<Message> findTop50ByConversationIdAndIdGreaterThanOrderByIdAsc(Long conversationId, Long id);

    @Query("select coalesce(max(m.seq), 0) from Message m where m.conversationId = :conversationId")
    Long getMaxSeqByConversationId(@Param("conversationId") Long conversationId);

    @Query("select count(m) from Message m where m.conversationId=:conversationId and (:lastReadMessageId is null or m.id > :lastReadMessageId) and m.senderId <> :userId")
    long countUnread(@Param("conversationId") Long conversationId, @Param("userId") Long userId, @Param("lastReadMessageId") Long lastReadMessageId);

    @Query(value = """
            select m.conversation_id as cid, count(*) as unread
            from messages m
            left join conversation_read_state rs
              on rs.conversation_id = m.conversation_id
             and rs.user_id = :userId
            where m.conversation_id in (:conversationIds)
              and m.sender_id <> :userId
              and (rs.last_read_message_id is null or m.id > rs.last_read_message_id)
            group by m.conversation_id
            """, nativeQuery = true)
    List<Object[]> countUnreadBatch(@Param("userId") Long userId, @Param("conversationIds") List<Long> conversationIds);
}
