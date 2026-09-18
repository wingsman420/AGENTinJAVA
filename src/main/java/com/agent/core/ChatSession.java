package com.agent.core;

import com.agent.llm.model.ChatMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次会话的多轮对话历史。
 *
 * <p>大模型本身**没有记忆**。所谓"多轮对话"，本质上每轮都把此前所有消息
 * 重新发一遍。所以这个类做的事情就是攒着历史，供 {@link Agent} 每轮整份发出去。
 * 这也解释了两个现象：聊得越久越贵（token 线性增长）、超出上下文窗口后
 * 最早的消息会被挤掉。
 *
 * <h2>为什么要 synchronized</h2>
 * Web API 可能同时收到同一个 sessionId 的两个请求（比如用户连点两次）。
 * 如果两个线程同时往 {@code messages} 里追加，轻则消息顺序错乱，
 * 重则 {@link ArrayList} 内部结构损坏。加锁保证每个会话串行处理 ——
 * 这也是语义上正确的行为：一次会话本就该是一问一答排队走。
 */
public class ChatSession {

    private final String id;
    private final Instant createdAt;
    private final String systemPrompt;
    private final List<ChatMessage> messages = new ArrayList<>();

    public ChatSession(String id, String systemPrompt) {
        this.id = id;
        this.createdAt = Instant.now();
        this.systemPrompt = systemPrompt;
        resetToSystemPrompt();
    }

    /**
     * 从数据库里的历史重建一个会话。
     *
     * <p>会话持久化以后，每次对话都要先从库里把历史读回来 —— 大模型 API 是无状态的，
     * 每轮都得把完整历史重新发一遍，所以内存里必须有一份完整的。
     *
     * @param history 已持久化的消息，**通常已包含 system 消息**（建会话时会一并写入）
     */
    public static ChatSession restore(String id, String systemPrompt, List<ChatMessage> history) {
        ChatSession session = new ChatSession(id, systemPrompt);

        if (history == null || history.isEmpty()) {
            // 空历史（比如会话刚建、还没写过消息）—— 用构造器放好的 system 消息即可
            return session;
        }

        List<ChatMessage> restored = new ArrayList<>(history);

        // 防御：历史里缺 system 消息时补一条。
        // system 消息承载 agent 的身份与行为边界，丢了它模型就不知道自己是干什么的、
        // 该不该用工具 —— 这属于"能跑但行为退化"，不补的话很难从现象上察觉。
        boolean hasSystem = restored.stream().anyMatch(m -> "system".equals(m.role()));
        if (!hasSystem && systemPrompt != null && !systemPrompt.isBlank()) {
            restored.add(0, ChatMessage.system(systemPrompt));
        }

        session.messages.clear();
        session.messages.addAll(restored);
        return session;
    }

    /** 建会话时固化的 system 提示，持久化层需要它来写 conversations 表。 */
    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 清空对话历史，只保留 system 提示。
     *
     * <p>清空后**必须重新放入 system 消息**，不能把历史清成空列表 ——
     * system 消息承载的是 agent 的身份和行为边界，丢了它模型就不知道
     * 该扮演什么角色、该不该用工具，"清空历史"会退化成"清空人格"。
     */
    public synchronized void clear() {
        resetToSystemPrompt();
    }

    private void resetToSystemPrompt() {
        messages.clear();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized void add(ChatMessage message) {
        messages.add(message);
    }

    /** 返回**副本**：调用方拿到后随便用，不会破坏会话内部状态，也不会拿到半截快照。 */
    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public synchronized int size() {
        return messages.size();
    }

    /** 只用于估算 token 开销，不做精确计数。 */
    public synchronized int approximateCharCount() {
        int total = 0;
        for (ChatMessage m : messages) {
            if (m.content() != null) {
                total += m.content().length();
            }
        }
        return total;
    }
}
