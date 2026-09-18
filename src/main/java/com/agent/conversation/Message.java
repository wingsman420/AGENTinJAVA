package com.agent.conversation;

import com.agent.llm.model.ChatMessage;
import com.agent.llm.model.ToolCall;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.List;

/**
 * 一条对话消息。
 *
 * <p>消息单独建表（而不是把整段历史塞进会话的一个 JSON 列）的好处：可以按会话
 * 流式读取、可以做历史搜索、追加消息只需 INSERT 而不必重写整行。
 *
 * <p>{@code (conversation_id, seq)} 上有唯一约束 —— 顺序是对话语义的一部分，
 * 出现重复序号说明写入逻辑有问题，让数据库直接拦下来比事后排查便宜。
 */
@Entity
@Table(name = "messages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_messages_conversation_seq",
                columnNames = {"conversation_id", "seq"}))
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    /** 会话内的顺序，从 1 开始。 */
    @Column(name = "seq", nullable = false)
    private int seq;

    /** system / user / assistant / tool */
    @Column(nullable = false, length = 20)
    private String role;

    /**
     * 长文本：工具结果可能很大（读文件上限 256KB）。
     *
     * <p><b>这里必须用 {@code @Lob} 而不是 {@code @Column(length = ...)}。</b>
     * 用 length 指定一个很大的值时，H2 会照单全收生成 {@code varchar(1000000)}，
     * 但 **MySQL 会拒绝** —— 它的 VARCHAR 上限是 65535 <b>字节</b>，
     * utf8mb4 下每字符最多 4 字节，所以最大只能到 16383 字符，
     * 建表时报 "Column length too big ... use BLOB or TEXT instead"。
     *
     * <p>{@code @Lob} 两边都能正确映射：MySQL → {@code longtext}，H2 → {@code CLOB}。
     * 这是个"本地测试全绿、一上生产就炸"的典型坑。
     */
    @Lob
    @Column(name = "content")
    private String content;

    /**
     * 模型请求调用的工具列表，以 JSON 存储。
     *
     * <p>用 {@link ToolCallsJsonConverter} 直接映射 {@code List<ToolCall>} ——
     * 见该转换器的注释，record 不能作实体，但可以作实体的字段。
     * 非工具调用消息这里为 null。
     */
    @Convert(converter = ToolCallsJsonConverter.class)
    @Column(name = "tool_calls_json", length = 10_000)
    private List<ToolCall> toolCalls;

    /** 工具结果消息用它指回是哪一次调用，非 tool 消息为 null。 */
    @Column(name = "tool_call_id", length = 64)
    private String toolCallId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Message() {
    }

    public Message(Conversation conversation, int seq, String role, String content,
                   List<ToolCall> toolCalls, String toolCallId) {
        this.conversation = conversation;
        this.seq = seq;
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.createdAt = Instant.now();
    }

    /** 从领域模型转成实体。seq 由调用方按会话内顺序给。 */
    public static Message from(Conversation conversation, int seq, ChatMessage message) {
        return new Message(conversation, seq, message.role(), message.content(),
                message.toolCalls(), message.toolCallId());
    }

    /** 转回领域模型，用于从数据库重建会话历史。 */
    public ChatMessage toChatMessage() {
        return new ChatMessage(role, content, toolCalls, toolCallId);
    }

    public Long getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public int getSeq() {
        return seq;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
