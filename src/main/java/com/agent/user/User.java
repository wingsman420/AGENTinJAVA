package com.agent.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户。
 *
 * <p><b>为什么是普通 class 而不是 record</b>：JPA 实体必须有无参构造器且不能是 final，
 * 而 record 两者都不满足（Hibernate 会显式跳过 record 的字节码增强）。
 * 这是本项目里少数几个不能用 record 表达的数据模型之一 ——
 * 其它像 {@code ChatMessage}/{@code ToolCall} 这些不落成实体的模型仍然保持 record。
 *
 * <p>表名用复数 {@code users} 而不是 {@code user}：后者在 PostgreSQL 里是保留字，
 * 用复数可以免去到处加引号的麻烦，也让以后换库少一个坑。
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt 哈希。**绝不存在明文密码**。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 要求的无参构造器。设为 protected 是为了不鼓励在业务代码里 new 出半成品对象。 */
    protected User() {
    }

    public User(String username, String passwordHash, String displayName) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 改密码时由 Service 传入已哈希的值，实体不碰明文。 */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
