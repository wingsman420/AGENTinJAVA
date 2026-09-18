package com.agent.core;

import com.agent.llm.model.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话历史的测试，重点是 clear 的语义。
 *
 * <p>对应 Lab03 的验收项 AT07（历史清空）。
 */
class ChatSessionTest {

    private static final String SYSTEM = "你是一个测试用的助手。";

    @Test
    void 构造时自动放入system消息() {
        ChatSession session = new ChatSession("s1", SYSTEM);

        assertThat(session.messages()).hasSize(1);
        assertThat(session.messages().get(0).role()).isEqualTo("system");
        assertThat(session.messages().get(0).content()).isEqualTo(SYSTEM);
    }

    @Test
    void 没有system提示时历史为空() {
        ChatSession session = new ChatSession("s1", null);

        assertThat(session.messages()).isEmpty();
    }

    @Test
    void clear后只剩system消息() {
        ChatSession session = new ChatSession("s1", SYSTEM);
        session.add(ChatMessage.user("第一个问题"));
        session.add(ChatMessage.assistant("第一个回答", null));
        session.add(ChatMessage.user("第二个问题"));

        assertThat(session.messages()).hasSize(4);

        session.clear();

        // 关键：不能清成空列表。system 消息承载 agent 的身份和行为边界，
        // 丢了它就等于把"人格"也清了，模型会不知道自己是干什么的。
        assertThat(session.messages()).hasSize(1);
        assertThat(session.messages().get(0).role()).isEqualTo("system");
        assertThat(session.messages().get(0).content()).isEqualTo(SYSTEM);
    }

    @Test
    void clear后会话id不变() {
        ChatSession session = new ChatSession("keep-me", SYSTEM);
        session.add(ChatMessage.user("问题"));

        session.clear();

        // 客户端可能还持有这个 id，换了 id 会让"清空历史"变成"换了个会话"
        assertThat(session.id()).isEqualTo("keep-me");
    }

    @Test
    void 没有system提示时clear得到空历史() {
        ChatSession session = new ChatSession("s1", null);
        session.add(ChatMessage.user("问题"));

        session.clear();

        assertThat(session.messages()).isEmpty();
    }

    @Test
    void messages返回的是副本改不动内部状态() {
        ChatSession session = new ChatSession("s1", SYSTEM);

        var snapshot = session.messages();
        session.add(ChatMessage.user("新消息"));

        // 之前拿到的副本不应被后续添加影响
        assertThat(snapshot).hasSize(1);
        assertThat(session.messages()).hasSize(2);
    }

    @Test
    void clear是可重复调用的() {
        ChatSession session = new ChatSession("s1", SYSTEM);
        session.add(ChatMessage.user("a"));

        session.clear();
        session.clear();

        assertThat(session.messages()).hasSize(1);
    }
}
