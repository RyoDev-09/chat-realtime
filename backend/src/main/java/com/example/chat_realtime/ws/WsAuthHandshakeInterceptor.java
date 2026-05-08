package com.example.chat_realtime.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@Component
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(WsAuthHandshakeInterceptor.class);
    private static final AtomicLong wsConnectCounter = new AtomicLong(0);
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletReq) {
            HttpServletRequest http = servletReq.getServletRequest();
            String auth = http.getHeader("Authorization");
            if (auth == null || auth.isBlank()) {
                auth = http.getParameter("access_token");
                if (auth != null && !auth.startsWith("Bearer ")) {
                    auth = "Bearer " + auth;
                }
            }
            Long userId = parseUserId(auth);
            if (userId != null) {
                attributes.put("authUserId", userId);
                long c = wsConnectCounter.incrementAndGet();
                if (c % 20 == 0) {
                    log.info("ws.connect.accept count={}", c);
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
    }

    private Long parseUserId(String auth) {
        if (auth == null) return null;
        auth = auth.trim();
        if (!auth.startsWith("Bearer ")) return null;
        String token = auth.substring("Bearer ".length()).trim();
        String prefix = "dev-token-user-";
        if (!token.startsWith(prefix)) return null;
        try {
            return Long.parseLong(token.substring(prefix.length()));
        } catch (Exception e) {
            return null;
        }
    }
}
