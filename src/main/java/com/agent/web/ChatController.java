package com.agent.web;

import com.agent.config.AgentProperties;
import com.agent.conversation.ConversationService;
import com.agent.core.Agent;
import com.agent.security.AppUserPrincipal;
import com.agent.web.dto.ChatRequestDto;
import com.agent.web.dto.ChatResponseDto;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对话接口。
 *
 * <p>职责严格限定为**接线**：把 HTTP 请求翻译成 Service 调用，把结果翻译回 JSON。
 * 事务边界、归属校验、消息落库全在 {@link ConversationService} 里 ——
 * 这样终端入口（{@code TerminalChatRunner}）才能复用同一套逻辑。
 *
 * <p>会话的增删改查在 {@link ConversationController}。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ConversationService conversations;
    private final Agent agent;
    private final AgentProperties props;

    public ChatController(ConversationService conversations, Agent agent, AgentProperties props) {
        this.conversations = conversations;
        this.agent = agent;
        this.props = props;
    }

    /**
     * 发一条消息，拿到模型回答。
     *
     * <p>{@code principal.id()} 是当前登录用户 —— 它会被传给 Service 用于校验
     * "这个会话是不是你的"。**绝不能从请求体里取 userId**，那是越权的经典入口。
     */
    @PostMapping("/chat")
    public ChatResponseDto chat(@AuthenticationPrincipal AppUserPrincipal principal,
                                @Valid @RequestBody ChatRequestDto request) {
        ConversationService.ChatResult result =
                conversations.chat(principal.id(), request.conversationId(), request.message());
        return new ChatResponseDto(result.conversationId(), result.reply(), result.historySize());
    }

    /** 服务状态。会话数只统计当前用户的。 */
    @GetMapping("/status")
    public Map<String, Object> status(@AuthenticationPrincipal AppUserPrincipal principal) {
        return Map.of(
                "status", "ok",
                "model", props.model(),
                "workspace", props.workspace().toString(),
                "tools", agent.activeToolNames(),
                "user", principal.getUsername(),
                "conversations", conversations.list(principal.id()).size());
    }
}
