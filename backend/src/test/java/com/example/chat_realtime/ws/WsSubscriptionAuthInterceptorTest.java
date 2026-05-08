package com.example.chat_realtime.ws;

import com.example.chat_realtime.auth.JwtService;
import com.example.chat_realtime.conversation.ConversationMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class WsSubscriptionAuthInterceptorTest {
    private ConversationMemberRepository repo;
    private JwtService jwtService;
    private WsSubscriptionAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        repo = mock(ConversationMemberRepository.class);
        jwtService = new JwtService("test-secret-change-me-test-secret-32-bytes", 86400);
        interceptor = new WsSubscriptionAuthInterceptor(repo, jwtService);
    }

    @Test
    void connectShouldExtractUserFromAuthorizationHeader() {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.CONNECT);
        acc.addNativeHeader("Authorization", "Bearer " + jwtService.issue(77L, "tester"));
        acc.setSessionAttributes(new HashMap<>());
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());

        Message<?> out = interceptor.preSend(msg, mock(MessageChannel.class));
        StompHeaderAccessor outAcc = StompHeaderAccessor.wrap(out);
        assertEquals(77L, outAcc.getSessionAttributes().get("authUserId"));
    }

    @Test
    void subscribeShouldRejectWhenNotAuthenticated() {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setDestination("/topic/conversations/10");
        acc.setSessionAttributes(new HashMap<>());
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());

        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(msg, mock(MessageChannel.class)));
        verify(repo, never()).findByConversationIdAndUserIdAndIsActiveTrue(anyLong(), anyLong());
    }

    @Test
    void subscribeShouldRejectWhenNotMember() {
        when(repo.findByConversationIdAndUserIdAndIsActiveTrue(10L, 5L)).thenReturn(Optional.empty());

        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setDestination("/topic/conversations/10");
        HashMap<String, Object> attrs = new HashMap<>();
        attrs.put("authUserId", 5L);
        acc.setSessionAttributes(attrs);
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());

        assertThrows(IllegalArgumentException.class, () -> interceptor.preSend(msg, mock(MessageChannel.class)));
    }

    @Test
    void subscribeShouldPassWhenMember() {
        when(repo.findByConversationIdAndUserIdAndIsActiveTrue(10L, 5L)).thenReturn(Optional.of(mock()));

        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        acc.setDestination("/topic/conversations/10");
        HashMap<String, Object> attrs = new HashMap<>();
        attrs.put("authUserId", 5L);
        acc.setSessionAttributes(attrs);
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());

        Message<?> out = interceptor.preSend(msg, mock(MessageChannel.class));
        assertNotNull(out);
    }
}
