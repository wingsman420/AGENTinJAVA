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
    private final List<ChatMessage> messages = new ArrayList<>();

    public ChatSession(String id, String systemPrompt) {
        this.id = id;
        this.createdAt = Instant.now();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            this.messages.add(ChatMessage.system(systemPrompt));
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
