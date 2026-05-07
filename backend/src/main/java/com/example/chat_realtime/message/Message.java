package com.example.chat_realtime.message;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Getter
@Setter
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;
    @Column(name = "seq", nullable = false)
    private Long seq;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "client_msg_id", nullable = false, length = 64)
    private String clientMsgId;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType = "TEXT";

    @Column(name = "content_json", nullable = false, columnDefinition = "json")
    private String contentJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
