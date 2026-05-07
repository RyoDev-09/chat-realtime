package com.example.chat_realtime.message;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.example.chat_realtime.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/conversations/{conversationId}/messages")
@RequiredArgsConstructor
public class MessageController {
    private final MessageService messageService;

    @PostMapping
    public ApiResponse<Message> send(
            @PathVariable Long conversationId,
            @RequestBody SendMessageRequest req) {
        Message m = messageService.send(conversationId, req.senderId(), req.text(), req.clientMsgId());
        return ApiResponse.ok(m, "message sent");
    }

    @GetMapping
    public ApiResponse<List<Message>> list(@PathVariable Long conversationId) {
        return ApiResponse.ok(messageService.list(conversationId));
    }

    public record SendMessageRequest(Long senderId, String text, String clientMsgId) {
    }

}
