package com.example.chat_realtime.auth;

import java.security.Principal;

public class DevAuthentication implements Principal {
    private final Long userId;

    public DevAuthentication(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
