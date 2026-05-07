package com.example.chat_realtime.auth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.chat_realtime.common.ApiResponse;
import com.example.chat_realtime.user.User;
import com.example.chat_realtime.user.UserRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
private final UserRepository userRepository;

@PostMapping("/login")
public ApiResponse<LoginResponse> login(@RequestBody LoginRequest req) {
    if (req.username() == null || req.username().isBlank()) {
        return ApiResponse.fail("username is required");
    }

    User user = userRepository.findByUsername(req.username())
            .orElseGet(() -> {
                User u = new User();
                u.setUsername(req.username());
                u.setDisplayName(req.displayName() == null ? req.username() : req.displayName());
                return userRepository.save(u);
            });

    // Fake token cho phase REST
    String token = "dev-token-user-" + user.getId();

    return ApiResponse.ok(new LoginResponse(token, user.getId(), user.getUsername(), user.getDisplayName()));
}

public record LoginRequest(String username, String displayName) {}
public record LoginResponse(String token, Long userId, String username, String displayName) {}
    
}
