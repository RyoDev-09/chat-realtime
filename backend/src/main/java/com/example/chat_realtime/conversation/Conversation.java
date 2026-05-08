package com.example.chat_realtime.conversation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversations")
@Getter
@Setter
public class Conversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
@Column(nullable=false, length=10)
private ConversationType type; // DIRECT, GROUP

@Column(length=255)
private String title;

@Column(name = "direct_pair_key", length = 64)
private String directPairKey;

@Column(name="created_by", nullable=false)
private Long createdBy;

@Column(name="created_at", nullable=false)
private LocalDateTime createdAt = LocalDateTime.now();

@Column(name="last_message_at")
private LocalDateTime lastMessageAt;

@Column(name="updated_at", nullable=false)
private LocalDateTime updatedAt = LocalDateTime.now();

@PreUpdate
public void preUpdate() {
    this.updatedAt = LocalDateTime.now();
}

}
