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

@PostMapping("/register")
public ApiResponse<LoginResponse> register(@RequestBody RegisterRequest req) {
    if (req.username() == null || req.username().isBlank()) {
        return ApiResponse.fail("username is required");
    }
    if (req.password() == null || req.password().isBlank()) {
        return ApiResponse.fail("password is required");
    }

    String username = req.username().trim();
    if (userRepository.findByUsername(username).isPresent()) {
        return ApiResponse.fail("username already exists");
    }

    User u = new User();
    u.setUsername(username);
    u.setDisplayName(req.displayName() == null || req.displayName().isBlank() ? username : req.displayName().trim());
    User saved = userRepository.save(u);

    String token = "dev-token-user-" + saved.getId();
    return ApiResponse.ok(new LoginResponse(token, saved.getId(), saved.getUsername(), saved.getDisplayName()), "register success");
}

@PostMapping("/login")
public ApiResponse<LoginResponse> login(@RequestBody LoginRequest req) {
    if (req.username() == null || req.username().isBlank()) {
        return ApiResponse.fail("username is required");
    }
    if (req.password() == null || req.password().isBlank()) {
        return ApiResponse.fail("password is required");
    }

    User user = userRepository.findByUsername(req.username().trim()).orElse(null);
    if (user == null) {
        return ApiResponse.fail("user not found");
    }

    String token = "dev-token-user-" + user.getId();
    return ApiResponse.ok(new LoginResponse(token, user.getId(), user.getUsername(), user.getDisplayName()), "login success");
}

public record RegisterRequest(String username, String displayName, String password) {}
public record LoginRequest(String username, String password) {}
public record LoginResponse(String token, Long userId, String username, String displayName) {}
    
}
