package com.agent.web;

import com.agent.conversation.ConversationService;
import com.agent.conversation.ConversationView;
import com.agent.security.AppUserPrincipal;
import com.agent.web.dto.ConversationDtos.CreateConversationRequest;
import com.agent.web.dto.ConversationDtos.RenameRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话的增删改查。
 *
 * <p>每个方法都把 {@code principal.id()} 传给 Service，由 Service 用
 * {@code findByIdAndUserId} 做归属校验。**路径里的 id 永远不可信** ——
 * 那是客户端可以随便改的值。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    /** 列出当前用户的全部会话，最近活跃的在前。 */
    @GetMapping
    public List<ConversationView> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return conversations.list(principal.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView create(@AuthenticationPrincipal AppUserPrincipal principal,
                                   @RequestBody(required = false) @Valid CreateConversationRequest request) {
        return conversations.create(principal.id(), request == null ? null : request.title());
    }

    /** 读会话详情，含完整消息历史。 */
    @GetMapping("/{id}")
    public ConversationView get(@AuthenticationPrincipal AppUserPrincipal principal,
                                @PathVariable Long id) {
        return conversations.get(principal.id(), id);
    }

    @PatchMapping("/{id}")
    public ConversationView rename(@AuthenticationPrincipal AppUserPrincipal principal,
                                   @PathVariable Long id,
                                   @Valid @RequestBody RenameRequest request) {
        return conversations.rename(principal.id(), id, request.title());
    }

    /** 删会话，连带删掉它的全部消息。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@AuthenticationPrincipal AppUserPrincipal principal,
                                      @PathVariable Long id) {
        conversations.delete(principal.id(), id);
        return Map.of("deleted", true, "conversationId", id);
    }

    /** 只清空消息、保留会话本身（对齐 CLI 的 clear 语义）。 */
    @DeleteMapping("/{id}/messages")
    public Map<String, Object> clearMessages(@AuthenticationPrincipal AppUserPrincipal principal,
                                             @PathVariable Long id) {
        conversations.clearMessages(principal.id(), id);
        return Map.of("cleared", true, "conversationId", id);
    }
}
