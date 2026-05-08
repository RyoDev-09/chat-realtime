package com.example.chat_realtime.auth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthUserResolver {
    private final JwtService jwtService;

    public Long resolveUserId(HttpServletRequest request) {
        Object fromAttr = request.getAttribute("authUserId");
        if (fromAttr instanceof Long l) return l;

        String auth = request.getHeader("Authorization");
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
