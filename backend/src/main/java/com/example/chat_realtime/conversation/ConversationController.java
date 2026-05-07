package com.example.chat_realtime.conversation;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.example.chat_realtime.common.ApiResponse;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService conversationService;

    @PostMapping
    public ApiResponse<Conversation> create(@RequestBody CreateConversationRequest req) {
        Conversation c = conversationService.create(
                req.creatorId(),
                req.type(),
                req.title(),
                req.memberIds());
        return ApiResponse.ok(c, "conversation created");
    }

    @GetMapping
    public ApiResponse<List<Conversation>> list(@RequestParam Long userId) {
        return ApiResponse.ok(conversationService.listByUser(userId));
    }

    public record CreateConversationRequest(
            Long creatorId,
            ConversationType type,
            String title,
            List<Long> memberIds) {
    }

}
