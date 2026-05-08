package com.example.chat_realtime.user;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.chat_realtime.common.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {
    private final UserRepository userRepository;

    @GetMapping
    public ApiResponse<List<UserListItem>> list(@RequestParam(required = false) Long excludeUserId) {
        List<UserListItem> users = userRepository.findAll().stream()
                .filter(u -> excludeUserId == null || !u.getId().equals(excludeUserId))
                .map(u -> new UserListItem(u.getId(), u.getUsername(), u.getDisplayName()))
                .toList();
        return ApiResponse.ok(users);
    }

    public record UserListItem(Long id, String username, String displayName) {
    }
}
