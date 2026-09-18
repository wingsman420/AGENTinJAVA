package com.agent.web;

import com.agent.config.AgentProperties;
import com.agent.core.Agent;
import com.agent.core.ChatSession;
import com.agent.core.SessionStore;
import com.agent.llm.LlmException;
import com.agent.web.dto.ChatRequestDto;
import com.agent.web.dto.ChatResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Web 入口：远程对话 API。
 *
 * <p>和终端入口共用同一个 {@link Agent} 和 {@link SessionStore}，
 * 所以两条路径的行为完全一致 —— 业务逻辑只写了一遍。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Agent agent;
    private final SessionStore sessions;
    private final AgentProperties props;

    public ChatController(Agent agent, SessionStore sessions, AgentProperties props) {
        this.agent = agent;
        this.sessions = sessions;
        this.props = props;
    }

    /**
     * 发一条消息，拿到回复。
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/chat \
     *      -H "Content-Type: application/json" \
     *      -d '{"message":"当前目录下有哪些文件？"}'
     * </pre>
     */
    @PostMapping("/chat")
    public ChatResponseDto chat(@RequestBody ChatRequestDto request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        ChatSession session = sessions.getOrCreate(request.sessionId());
        log.debug("会话 {} 收到消息（历史 {} 条）", session.id(), session.size());

        String reply = agent.chat(session, request.message());
        return new ChatResponseDto(session.id(), reply, session.size());
    }

    /** 服务状态，用来确认服务起来了、以及当前用的是哪个模型和工作目录。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "status", "ok",
                "model", props.model(),
                "workspace", props.workspace().toString(),
                "tools", agent.activeToolNames(),
                "sessions", sessions.count());
    }

    /** 列出当前所有活跃会话 id。 */
    @GetMapping("/sessions")
    public List<String> sessions() {
        return sessions.activeSessionIds();
    }

    /** 清掉一个会话的上下文，下次同 id 请求会从零开始。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        boolean removed = sessions.remove(sessionId);
        return Map.of("sessionId", sessionId, "cleared", removed);
    }

    // 异常 → HTTP 状态码的映射统一放在 ApiExceptionHandler（@RestControllerAdvice），
    // 避免同一类异常在不同 Controller 里返回不同状态码。
}
