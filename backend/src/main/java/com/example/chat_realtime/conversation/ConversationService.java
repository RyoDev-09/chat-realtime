package com.example.chat_realtime.conversation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.chat_realtime.message.MessageRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ConversationService {
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationReadStateRepository readStateRepository;
    private final MessageRepository messageRepository;

    @Transactional
    public Conversation create(Long creatorId, ConversationType type, String title, List<Long> memberIds) {
        // Business flow: create/open a conversation.
        // DIRECT is idempotent (A->B and B->A must resolve to the same conversation),
        // while GROUP always creates a new conversation with the selected members.
        validateCreateInput(creatorId, type, memberIds);

        Set<Long> normalizedMembers = new LinkedHashSet<>();
        if (memberIds != null) {
            for (Long uid : memberIds) {
                if (uid != null && uid > 0 && !uid.equals(creatorId)) {
                    normalizedMembers.add(uid);
                }
            }
        }

        if (type == ConversationType.DIRECT) {
            Long otherUserId = normalizedMembers.iterator().next();
            String pairKey = buildDirectPairKey(creatorId, otherUserId);
            Conversation existing = conversationRepository.findByDirectPairKey(pairKey).orElse(null);
            if (existing != null) {
                // Important business rule: "delete chat" is a per-user hide (member.isActive=false),
                // not a hard delete. If the same DIRECT is opened again, reactivate both sides so
                // sending/listing works without creating duplicate DIRECT rows.
                upsertActiveMember(existing.getId(), creatorId);
                upsertActiveMember(existing.getId(), otherUserId);
                return existing;
            }
        }

        Conversation c = new Conversation();
        c.setCreatedBy(creatorId);
        c.setType(type);
        c.setTitle(type == ConversationType.GROUP ? title : null);
        if (type == ConversationType.DIRECT) {
            Long otherUserId = normalizedMembers.iterator().next();
            c.setDirectPairKey(buildDirectPairKey(creatorId, otherUserId));
        }
        c = conversationRepository.save(c);

        upsertActiveMember(c.getId(), creatorId);

        for (Long uid : normalizedMembers) {
            upsertActiveMember(c.getId(), uid);
        }
        return c;
    }

    private void upsertActiveMember(Long conversationId, Long userId) {
        // Upsert instead of blindly inserting to support restoring hidden DIRECT chats
        // and to avoid duplicate membership records when a flow is retried.
        ConversationMember m = memberRepository.findByConversationIdAndUserId(conversationId, userId).orElseGet(() -> {
            ConversationMember created = new ConversationMember();
            created.setConversationId(conversationId);
            created.setUserId(userId);
            return created;
        });
        m.setIsActive(true);
        memberRepository.save(m);
    }

    private void validateCreateInput(Long creatorId, ConversationType type, List<Long> memberIds) {
        if (creatorId == null || creatorId <= 0) {
            throw new IllegalArgumentException("creatorId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (memberIds == null || memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds is required");
        }

        Set<Long> normalizedMembers = new LinkedHashSet<>();
        for (Long uid : memberIds) {
            if (uid != null && uid > 0 && !uid.equals(creatorId)) {
                normalizedMembers.add(uid);
            }
        }

        if (type == ConversationType.DIRECT && normalizedMembers.size() != 1) {
            throw new IllegalArgumentException("DIRECT requires exactly 1 member (excluding creator)");
        }
        if (type == ConversationType.GROUP && normalizedMembers.size() < 2) {
            throw new IllegalArgumentException("GROUP requires at least 2 members (excluding creator)");
        }
    }

    private String buildDirectPairKey(Long a, Long b) {
        long min = Math.min(a, b);
        long max = Math.max(a, b);
        return min + ":" + max;
    }

    public List<Conversation> listByUser(Long userId) {
        List<Long> conversationIds = memberRepository.findByUserIdAndIsActiveTrue(userId)
                .stream().map(ConversationMember::getConversationId).toList();
        return conversationRepository.findAllById(conversationIds);
    }

    public List<ConversationListItemDto> listByUserWithUnread(Long userId, int limit) {
        // Conversation list is the source of truth for unread badges/highlight in the UI.
        // Unread is calculated in one batch query to avoid N queries for N conversations.
        List<Conversation> conversations = listByUser(userId);
        if (conversations.isEmpty()) return List.of();

        List<Long> conversationIds = conversations.stream().map(Conversation::getId).toList();
        var unreadRows = messageRepository.countUnreadBatch(userId, conversationIds);

        java.util.Map<Long, Long> unreadMap = new java.util.HashMap<>();
        for (Object[] row : unreadRows) {
            Long cid = ((Number) row[0]).longValue();
            Long unread = ((Number) row[1]).longValue();
            unreadMap.put(cid, unread);
        }

        List<ConversationListItemDto> out = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        for (Conversation c : conversations.stream().sorted((a,b)-> {
            if (a.getLastMessageAt() == null && b.getLastMessageAt() == null) return Long.compare(b.getId(), a.getId());
            if (a.getLastMessageAt() == null) return 1;
            if (b.getLastMessageAt() == null) return -1;
            return b.getLastMessageAt().compareTo(a.getLastMessageAt());
        }).limit(safeLimit).toList()) {
            long unread = unreadMap.getOrDefault(c.getId(), 0L);
            out.add(new ConversationListItemDto(c, unread));
        }
        return out;
    }

    public void hideForUser(Long conversationId, Long userId) {
        // Messenger-like delete: hide the conversation only for the current user.
        // Other members keep their history and membership.
        ConversationMember member = memberRepository.findByConversationIdAndUserIdAndIsActiveTrue(conversationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("conversation not found"));
        member.setIsActive(false);
        memberRepository.save(member);
    }

    public List<ConversationMember> listActiveMembers(Long conversationId) {
        return memberRepository.findByConversationIdAndIsActiveTrue(conversationId);
    }

    public record ConversationListItemDto(Long id, ConversationType type, String title, String lastMessageAt, Long unreadCount) {
        public ConversationListItemDto(Conversation c, long unreadCount) {
            this(c.getId(), c.getType(), c.getTitle(), c.getLastMessageAt() == null ? null : c.getLastMessageAt().toString(), unreadCount);
        }
    }

}
