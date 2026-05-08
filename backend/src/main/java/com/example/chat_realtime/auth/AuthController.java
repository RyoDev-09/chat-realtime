package com.example.chat_realtime.auth;

import com.example.chat_realtime.common.ApiResponse;
import com.example.chat_realtime.user.User;
import com.example.chat_realtime.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@RequestBody RegisterRequest req) {
        if (req.username() == null || req.username().isBlank()) {
            return ApiResponse.fail("username is required");
        }
        if (req.password() == null || req.password().isBlank()) {
            return ApiResponse.fail("password is required");
        }
        if (req.password().length() < 8) {
            return ApiResponse.fail("password must be at least 8 characters");
        }

        String username = req.username().trim();
        if (userRepository.findByUsername(username).isPresent()) {
            return ApiResponse.fail("username already exists");
        }

        User u = new User();
        u.setUsername(username);
        u.setDisplayName(req.displayName() == null || req.displayName().isBlank() ? username : req.displayName().trim());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        User saved = userRepository.save(u);

        String token = jwtService.issue(saved.getId(), saved.getUsername());
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
        if (user == null || user.getPasswordHash() == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            return ApiResponse.fail("invalid username or password");
        }

        String token = jwtService.issue(user.getId(), user.getUsername());
        return ApiResponse.ok(new LoginResponse(token, user.getId(), user.getUsername(), user.getDisplayName()), "login success");
    }

    public record RegisterRequest(String username, String displayName, String password) {}
    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, Long userId, String username, String displayName) {}
}
