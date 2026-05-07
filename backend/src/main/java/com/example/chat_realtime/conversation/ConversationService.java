package com.example.chat_realtime.conversation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ConversationService {
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;

    @Transactional
    public Conversation create(Long creatorId, ConversationType type, String title, List<Long> memberIds) {
        Conversation c = new Conversation();
        c.setCreatedBy(creatorId);
        c.setType(type);
        c.setTitle(title);
        c = conversationRepository.save(c);

        // add creator
        ConversationMember self = new ConversationMember();
        self.setConversationId(c.getId());
        self.setUserId(creatorId);
        memberRepository.save(self);

        // add others
        if (memberIds != null) {
            for (Long uid : memberIds) {
                if (uid.equals(creatorId))
                    continue;
                ConversationMember m = new ConversationMember();
                m.setConversationId(c.getId());
                m.setUserId(uid);
                memberRepository.save(m);
            }
        }
        return c;
    }

    public List<Conversation> listByUser(Long userId) {
        List<Long> conversationIds = memberRepository.findByUserIdAndIsActiveTrue(userId)
                .stream().map(ConversationMember::getConversationId).toList();
        return conversationRepository.findAllById(conversationIds);
    }

}
