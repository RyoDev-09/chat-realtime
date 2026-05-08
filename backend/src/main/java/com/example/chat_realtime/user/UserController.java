package com.example.chat_realtime.user;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.chat_realtime.auth.AuthUserResolver;
import com.example.chat_realtime.common.ApiResponse;
import com.example.chat_realtime.common.ForbiddenException;
import com.example.chat_realtime.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserRepository userRepository;
    private final AuthUserResolver authUserResolver;

    @GetMapping
    public ApiResponse<List<UserListItem>> list(@RequestParam(required = false) Long excludeUserId, HttpServletRequest request) {
        Long authUserId = authUserResolver.resolveUserId(request);
        if (authUserId == null) throw new UnauthorizedException("unauthorized");
        if (excludeUserId != null && !excludeUserId.equals(authUserId)) throw new ForbiddenException("forbidden");
        List<UserListItem> users = userRepository.findAll().stream()
                .filter(u -> excludeUserId == null || !u.getId().equals(excludeUserId))
                .map(u -> new UserListItem(u.getId(), u.getUsername(), u.getDisplayName()))
                .toList();
        return ApiResponse.ok(users);
    }

    public record UserListItem(Long id, String username, String displayName) {
    }
}
