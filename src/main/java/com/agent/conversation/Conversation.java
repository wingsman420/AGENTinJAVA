package com.agent.conversation;

import com.agent.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一次会话（一段对话）。
 *
 * <p>归属关系用 {@code @ManyToOne} 而不是裸的 {@code userId} 字段，这样 Hibernate
 * 会生成真正的外键约束。注意 {@code FetchType.LAZY}：因为配置里关掉了
 * {@code open-in-view}，任何用到 {@code conversation.getUser()} 的地方都必须在
 * 事务内完成，否则会抛懒加载异常。本项目的 Service 层保证了这一点。
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 200)
    private String title;

    /**
     * 建会话时固化的 system 提示。
     *
     * <p><b>必须存下来，不能在读的时候重新生成。</b> {@code Agent.systemPrompt()}
     * 是运行时动态拼的 —— 里面含 workspace 路径和当前注册的工具名。如果每次从数据库
     * 重建会话时都重新生成，那么一旦配置或工具集发生变化，同一个会话前后两轮的
     * system 提示就会不一致，模型的行为设定会漂移。
     */
    @Column(name = "system_prompt", nullable = false, length = 4000)
    private String systemPrompt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    public Conversation(User user, String title, String systemPrompt) {
        this.user = user;
        this.title = title;
        this.systemPrompt = systemPrompt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** 每次追加消息后调用，让会话列表能按最近活跃排序。 */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
