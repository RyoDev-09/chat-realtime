package com.example.chat_realtime.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class DevTokenAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Long userId = resolveBearerUserId(request.getHeader("Authorization"));
        if (userId != null) {
            request.setAttribute("authUserId", userId);
        }

        filterChain.doFilter(request, response);
    }

    private Long resolveBearerUserId(String auth) {
        if (auth == null) return null;
        auth = auth.trim();
        if (!auth.startsWith("Bearer ")) return null;
        String token = auth.substring("Bearer ".length()).trim();
        try {
            return jwtService.parseUserId(token);
        } catch (Exception ignored) {
            return null;
        }
    }
}
