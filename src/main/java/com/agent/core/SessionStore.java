package com.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话仓库：终端入口和 Web 入口共用同一份会话表。
 *
 * <p>这样设计有个直接好处 —— 你在终端里聊到一半，可以切到浏览器用同一个
 * sessionId 接着聊，上下文是连着的。
 *
 * <h2>为什么用 ConcurrentHashMap</h2>
 * Web 请求由 Tomcat 的多个线程并发处理，多个会话可能同时创建。
 * {@code computeIfAbsent} 是原子操作，能保证同一个 sessionId 不会被创建出两个实例
 * —— 如果用 {@code containsKey} 再 {@code put}，两个并发请求就可能各建一个，
 * 后一个把前一个覆盖掉，用户会发现"聊过的内容丢了"。
 *
 * <h2>已知限制</h2>
 * 会话只存在内存里，进程一重启全没了。做成持久化（写文件或数据库）是后续
 * 可以扩展的方向，目前对实验项目来说内存足够。
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    /** 单个会话的历史长度提醒阈值，超过就记一条警告（不强制截断，交由用户决定）。 */
    private static final int LONG_SESSION_WARN_CHARS = 100_000;

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Agent agent;

    public SessionStore(Agent agent) {
        this.agent = agent;
    }

    /**
     * 取已有会话，没有就新建。
     *
     * @param sessionId 客户端给的会话标识；为空时自动生成一个 UUID
     */
    public ChatSession getOrCreate(String sessionId) {
        String id = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId.trim();

        ChatSession session = sessions.computeIfAbsent(id, key -> {
            log.info("创建新会话: {}", key);
            return new ChatSession(key, agent.systemPrompt());
        });

        if (session.approximateCharCount() > LONG_SESSION_WARN_CHARS) {
            log.warn("会话 {} 历史已达约 {} 字符，token 开销会明显上升", id, session.approximateCharCount());
        }
        return session;
    }

    public List<String> activeSessionIds() {
        return List.copyOf(sessions.keySet());
    }

    public int count() {
        return sessions.size();
    }

    /** 清掉一个会话，下次同 id 请求会开启全新上下文。 */
    public boolean remove(String sessionId) {
        return sessions.remove(sessionId) != null;
    }
}
