package com.example.chat_realtime.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class AuthUserResolver {
    public Long resolveUserId(HttpServletRequest request) {
        Object fromAttr = request.getAttribute("authUserId");
        if (fromAttr instanceof Long l) return l;

        String auth = request.getHeader("Authorization");
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
