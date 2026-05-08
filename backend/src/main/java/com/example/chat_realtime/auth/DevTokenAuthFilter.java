package com.example.chat_realtime.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class DevTokenAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String auth = request.getHeader("Authorization");
        if (auth != null) {
            auth = auth.trim();
            if (auth.startsWith("Bearer ")) {
                String token = auth.substring("Bearer ".length()).trim();
                String prefix = "dev-token-user-";
                if (token.startsWith(prefix)) {
                    try {
                        Long userId = Long.parseLong(token.substring(prefix.length()));
                        request.setAttribute("authUserId", userId);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
