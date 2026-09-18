package com.agent.conversation;

import com.agent.llm.model.ChatMessage;
import com.agent.llm.model.ToolCall;
import com.agent.user.User;
import com.agent.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息持久化的测试。
 *
 * <p><b>这个类同时是一次"假设验证"。</b> 本项目把 record 集合
 * （{@code List<ToolCall>}）直接作为 JPA 实体字段，靠 {@link AttributeConverter}
 * 映射成 JSON 列 —— 这个结论来自对 Hibernate 绑定源码的阅读（它判定"是否集合映射"
 * 只看注解、不看 Java 类型），**动手时并没有端到端跑通过**。
 *
 * <p>如果这个假设不成立，本类的第一个测试会立刻失败，退路是把实体字段改成
 * {@code String toolCallsJson} 手工转换。
 */
@DataJpaTest
@ActiveProfiles("test")
class MessagePersistenceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    /** 用来清一级缓存，强制从数据库重新读 —— 否则测的是内存里的同一个对象。 */
    @PersistenceContext
    private EntityManager entityManager;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(new User("tester", "hash", "测试用户"));
        conversation = conversationRepository.save(
                new Conversation(user, "测试会话", "你是一个测试助手。"));
    }

    // ---------- 核心：record 集合的往返 ----------

    @Test
    void 工具调用消息的toolCalls能往返存取() {
        ToolCall call = new ToolCall("call_abc123", "function",
                new ToolCall.Function("readFile", "{\"path\":\"pom.xml\"}"));
        Message saved = messageRepository.saveAndFlush(
                new Message(conversation, 3, "assistant", "", List.of(call), null));

        entityManager.clear();   // 清缓存，强制从库里读回来

        Message loaded = messageRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getRole()).isEqualTo("assistant");
        assertThat(loaded.getToolCalls()).isNotNull().hasSize(1);
        ToolCall roundTripped = loaded.getToolCalls().get(0);
        // 逐个字段核对，确保 JSON 序列化没有丢字段、没有改名
        assertThat(roundTripped.id()).isEqualTo("call_abc123");
        assertThat(roundTripped.type()).isEqualTo("function");
        assertThat(roundTripped.function().name()).isEqualTo("readFile");
        assertThat(roundTripped.function().arguments()).isEqualTo("{\"path\":\"pom.xml\"}");
    }

    @Test
    void 一轮响应里的多个工具调用能全部保住() {
        List<ToolCall> calls = List.of(
                new ToolCall("call_1", "function", new ToolCall.Function("listFiles", "{\"path\":\".\"}")),
                new ToolCall("call_2", "function", new ToolCall.Function("readFile", "{\"path\":\"README.md\"}")));
        Message saved = messageRepository.saveAndFlush(
                new Message(conversation, 3, "assistant", "", calls, null));

        entityManager.clear();

        Message loaded = messageRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getToolCalls()).hasSize(2);
        assertThat(loaded.getToolCalls()).extracting(ToolCall::id)
                .containsExactly("call_1", "call_2");
    }

    @Test
    void 非工具消息的toolCalls存为null而不是空数组() {
        Message saved = messageRepository.saveAndFlush(
                new Message(conversation, 1, "user", "你好", null, null));

        entityManager.clear();

        assertThat(messageRepository.findById(saved.getId()).orElseThrow().getToolCalls())
                .isNull();
    }

    @Test
    void 工具结果消息的toolCallId能存取() {
        Message saved = messageRepository.saveAndFlush(
                new Message(conversation, 4, "tool", "文件内容：...", null, "call_abc123"));

        entityManager.clear();

        Message loaded = messageRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getToolCallId()).isEqualTo("call_abc123");
        assertThat(loaded.getToolCalls()).isNull();
    }

    // ---------- 领域模型的双向转换 ----------

    @Test
    void 能在这两个模型之间来回转换() {
        ToolCall call = new ToolCall("call_1", "function",
                new ToolCall.Function("searchCode", "{\"keyword\":\"x\"}"));
        ChatMessage original = ChatMessage.assistant("", List.of(call));

        ChatMessage restored = Message.from(conversation, 3, original).toChatMessage();

        assertThat(restored).isEqualTo(original);
    }

    // ---------- 顺序与查询 ----------

    @Test
    void 消息按seq顺序读回() {
        messageRepository.save(new Message(conversation, 3, "assistant", "第三", null, null));
        messageRepository.save(new Message(conversation, 1, "user", "第一", null, null));
        messageRepository.save(new Message(conversation, 2, "assistant", "第二", null, null));
        messageRepository.flush();
        entityManager.clear();

        assertThat(messageRepository.findByConversationIdOrderBySeqAsc(conversation.getId()))
                .extracting(Message::getContent)
                .containsExactly("第一", "第二", "第三");
    }

    @Test
    void 能查到当前最大seq用于追加() {
        messageRepository.save(new Message(conversation, 1, "user", "a", null, null));
        messageRepository.save(new Message(conversation, 2, "assistant", "b", null, null));
        messageRepository.flush();

        assertThat(messageRepository.findTopByConversationIdOrderBySeqDesc(conversation.getId()))
                .map(Message::getSeq)
                .contains(2);
    }

    @Test
    void 清空会话的消息但保留会话本身() {
        messageRepository.save(new Message(conversation, 1, "user", "a", null, null));
        messageRepository.flush();

        messageRepository.deleteByConversationId(conversation.getId());
        messageRepository.flush();

        assertThat(messageRepository.countByConversationId(conversation.getId())).isZero();
        assertThat(conversationRepository.findById(conversation.getId())).isPresent();
    }

    // ---------- 归属查询（越权防线） ----------

    @Test
    void 按归属用户查会话查不到别人的() {
        User other = userRepository.save(new User("other", "hash", "另一个人"));
        Conversation othersConversation = conversationRepository.save(
                new Conversation(other, "别人的会话", "system"));

        // 用自己的 id 查自己的 —— 查得到
        assertThat(conversationRepository
                .findByIdAndUserId(conversation.getId(), conversation.getUser().getId()))
                .isPresent();

        // 用自己的 id 查别人的 —— 必须查不到（这是越权防线）
        assertThat(conversationRepository
                .findByIdAndUserId(othersConversation.getId(), conversation.getUser().getId()))
                .isEmpty();
    }
}
