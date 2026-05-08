package com.example.chat_realtime.message;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.example.chat_realtime.auth.AuthUserResolver;
import com.example.chat_realtime.common.ApiResponse;
import com.example.chat_realtime.common.ForbiddenException;
import com.example.chat_realtime.common.UnauthorizedException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/conversations/{conversationId}/messages")
@RequiredArgsConstructor
public class MessageController {
    private final MessageService messageService;
    private final AuthUserResolver authUserResolver;

    @PostMapping
    public ApiResponse<Message> send(
            @PathVariable Long conversationId,
            @Valid @RequestBody SendMessageRequest req,
            HttpServletRequest request) {
        Long authUserId = authUserResolver.resolveUserId(request);
        if (authUserId == null) throw new UnauthorizedException("unauthorized");
        if (!authUserId.equals(req.senderId())) {
            throw new ForbiddenException("forbidden: senderId mismatch");
        }
        Long effectiveSenderId = authUserId;
        Message m = messageService.send(conversationId, effectiveSenderId, req.text(), req.clientMsgId());
        return ApiResponse.ok(m, "message sent");
    }

    @GetMapping
    public ApiResponse<List<Message>> list(
            @PathVariable Long conversationId,
            @RequestParam(required = false) Long cursorId) {
        if (cursorId == null) {
            return ApiResponse.ok(messageService.list(conversationId));
        }
        return ApiResponse.ok(messageService.listSince(conversationId, cursorId));
    }

    @PostMapping("/mark-read")
    public ApiResponse<Boolean> markRead(
            @PathVariable Long conversationId,
            @Valid @RequestBody MarkReadRequest req,
            HttpServletRequest request) {
        Long authUserId = authUserResolver.resolveUserId(request);
        if (authUserId == null) throw new UnauthorizedException("unauthorized");
        if (!authUserId.equals(req.userId())) {
            throw new ForbiddenException("forbidden: userId mismatch");
        }
        Long effectiveUserId = authUserId;
        messageService.markRead(conversationId, effectiveUserId);
        return ApiResponse.ok(true, "marked as read");
    }

    public record SendMessageRequest(@NotNull Long senderId, @NotBlank String text, String clientMsgId) {
    }

    public record MarkReadRequest(@NotNull Long userId) {
    }

}
