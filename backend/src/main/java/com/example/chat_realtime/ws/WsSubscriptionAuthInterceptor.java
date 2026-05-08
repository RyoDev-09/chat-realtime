package com.example.chat_realtime.ws;

import com.example.chat_realtime.auth.JwtService;
import com.example.chat_realtime.conversation.ConversationMemberRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class WsSubscriptionAuthInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(WsSubscriptionAuthInterceptor.class);
    private static final AtomicLong rejectCounter = new AtomicLong(0);
    private final ConversationMemberRepository memberRepository;
    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            // STOMP CONNECT can carry native headers. Store authUserId in the WS session so
            // later SUBSCRIBE frames can be authorized without trusting client-supplied topics.
            String auth = accessor.getFirstNativeHeader("Authorization");
            Long fromConnect = parseUserIdFromBearer(auth);
            if (fromConnect != null && accessor.getSessionAttributes() != null) {
                accessor.getSessionAttributes().put("authUserId", fromConnect);
            }
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String dest = accessor.getDestination();
            if (dest != null && dest.startsWith("/topic/users/") && dest.endsWith("/conversations")) {
                // User-list topic drives unread badge/highlight. It is private per user:
                // user A must never subscribe to /topic/users/B/conversations.
                Long destUserId = parseUserTopicId(dest);
                Long userId = (Long) accessor.getSessionAttributes().get("authUserId");
                if (destUserId == null || userId == null || !destUserId.equals(userId)) {
                    long c = rejectCounter.incrementAndGet();
                    log.warn("ws.subscribe.reject reason=forbidden-user-topic userId={} dest={} count={}", userId, dest, c);
                    throw new IllegalArgumentException("ws forbidden user topic subscribe");
                }
            }
            if (dest != null && dest.startsWith("/topic/conversations/")) {
                // Conversation topic carries actual message payloads. Require active membership
                // so a client cannot guess a conversation id and subscribe to someone else's chat.
                Long conversationId = parseConversationId(dest);
                Long userId = (Long) accessor.getSessionAttributes().get("authUserId");
                if (conversationId == null || userId == null) {
                    long c = rejectCounter.incrementAndGet();
                    log.warn("ws.subscribe.reject reason=unauthorized dest={} count={}", dest, c);
                    throw new IllegalArgumentException("ws unauthorized subscribe");
                }
                boolean isMember = memberRepository.findByConversationIdAndUserIdAndIsActiveTrue(conversationId, userId).isPresent();
                if (!isMember) {
                    long c = rejectCounter.incrementAndGet();
                    log.warn("ws.subscribe.reject reason=forbidden userId={} conversationId={} count={}", userId, conversationId, c);
                    throw new IllegalArgumentException("ws forbidden subscribe");
                }
            }
        }
        return message;
    }

    private Long parseUserTopicId(String dest) {
        try {
            String[] parts = dest.split("/");
            return Long.parseLong(parts[3]);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseConversationId(String dest) {
        try {
            String[] parts = dest.split("/");
            return Long.parseLong(parts[parts.length - 1]);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseUserIdFromBearer(String auth) {
        if (auth == null) return null;
        auth = auth.trim();
        if (!auth.startsWith("Bearer ")) return null;
        String token = auth.substring("Bearer ".length()).trim();
        try {
            return jwtService.parseUserId(token);
        } catch (Exception e) {
            return null;
        }
    }

}
