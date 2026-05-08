package com.example.chat_realtime.conversation;

import lombok.RequiredArgsConstructor;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import com.example.chat_realtime.auth.AuthUserResolver;
import com.example.chat_realtime.user.User;
import com.example.chat_realtime.user.UserRepository;

import com.example.chat_realtime.common.ApiResponse;
import com.example.chat_realtime.common.ForbiddenException;
import com.example.chat_realtime.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);
    private final ConversationService conversationService;
    private final UserRepository userRepository;
    private final AuthUserResolver authUserResolver;

    @PostMapping
    public ApiResponse<Conversation> create(@Valid @RequestBody CreateConversationRequest req, HttpServletRequest request) {
        try {
            Long authUserId = authUserResolver.resolveUserId(request);
            if (authUserId == null) throw new UnauthorizedException("unauthorized");
            if (!authUserId.equals(req.creatorId())) {
                throw new ForbiddenException("forbidden: creatorId mismatch");
            }
            Long effectiveUserId = authUserId;
            Conversation c = conversationService.create(
                    effectiveUserId,
                    req.type(),
                    req.title(),
                    req.memberIds());
            return ApiResponse.ok(c, "conversation created");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<ConversationService.ConversationListItemDto>> list(@RequestParam Long userId, @RequestParam(defaultValue = "50") Integer limit, HttpServletRequest request) {
        Long authUserId = authUserResolver.resolveUserId(request);
        if (authUserId == null) throw new UnauthorizedException("unauthorized");
        if (!authUserId.equals(userId)) {
            throw new ForbiddenException("forbidden: userId mismatch");
        }
        Long effectiveUserId = authUserId;
        long t0 = System.currentTimeMillis();
        var data = conversationService.listByUserWithUnread(effectiveUserId, limit == null ? 50 : limit);
        long tookMs = System.currentTimeMillis() - t0;
        log.info("conversations.list userId={} limit={} count={} tookMs={}", effectiveUserId, limit, data.size(), tookMs);
        return ApiResponse.ok(data);
    }

    @DeleteMapping("/{conversationId}")
    public ApiResponse<Boolean> hide(@PathVariable Long conversationId, @RequestParam Long userId, HttpServletRequest request) {
        Long authUserId = authUserResolver.resolveUserId(request);
        if (authUserId == null) throw new UnauthorizedException("unauthorized");
        if (!authUserId.equals(userId)) {
            throw new ForbiddenException("forbidden: userId mismatch");
        }
        conversationService.hideForUser(conversationId, authUserId);
        return ApiResponse.ok(true, "conversation deleted");
    }

    @GetMapping("/{conversationId}/direct-peer")
    public ApiResponse<DirectPeerDto> directPeer(@PathVariable Long conversationId, @RequestParam Long userId, HttpServletRequest request) {
        Long authUserId = authUserResolver.resolveUserId(request);
        if (authUserId == null) throw new UnauthorizedException("unauthorized");
        if (!authUserId.equals(userId)) {
            throw new ForbiddenException("forbidden: userId mismatch");
        }
        Long effectiveUserId = authUserId;
        Conversation c = conversationService.listByUser(effectiveUserId).stream().filter(x -> x.getId().equals(conversationId)).findFirst().orElse(null);
        if (c == null) return ApiResponse.fail("conversation not found");
        if (c.getType() != ConversationType.DIRECT) return ApiResponse.fail("conversation is not DIRECT");

        Long peerId = conversationService.listActiveMembers(conversationId).stream()
                .map(ConversationMember::getUserId)
                .filter(uid -> !uid.equals(effectiveUserId))
                .findFirst().orElse(null);
        if (peerId == null) return ApiResponse.fail("direct peer not found");

        User u = userRepository.findById(peerId).orElse(null);
        if (u == null) return ApiResponse.fail("user not found");

        return ApiResponse.ok(new DirectPeerDto(u.getId(), u.getUsername(), u.getDisplayName()), "OK");
    }

    public record DirectPeerDto(Long id, String username, String displayName) {}

    public record CreateConversationRequest(
            @NotNull Long creatorId,
            @NotNull ConversationType type,
            String title,
            @NotNull List<Long> memberIds) {
    }

}
