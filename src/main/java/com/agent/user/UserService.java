package com.agent.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 用户注册与查询。
 *
 * <p>注册的职责边界很明确：**校验用户名唯一 + 哈希密码 + 落库**。
 * 密码的哈希交给 {@link PasswordEncoder}，本类不出现任何自己实现的加密逻辑 ——
 * 密码学的事自己写几乎一定出错。
 */
@Service
public class UserService {

    private static final int USERNAME_MAX = 50;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册新用户。
     *
     * @param rawPassword 明文密码，**只在这个方法的内存里存在**，
     *                    落库前就被替换成 BCrypt 哈希
     */
    @Transactional
    public User register(String username, String rawPassword, String displayName) {
        String name = username == null ? "" : username.strip();
        if (name.isEmpty() || name.length() > USERNAME_MAX) {
            throw new IllegalArgumentException("用户名长度需在 1~" + USERNAME_MAX + " 之间");
        }
        if (users.existsByUsername(name)) {
            throw new IllegalArgumentException("用户名已被占用");
        }
        String display = (displayName == null || displayName.isBlank()) ? name : displayName.strip();
        return users.save(new User(name, passwordEncoder.encode(rawPassword), display));
    }

    @Transactional(readOnly = true)
    public User requireById(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new NoSuchElementException("用户不存在: " + id));
    }

    /**
     * 取（或创建）终端入口使用的内置用户。
     *
     * <p>终端在一台本机上运行，谁坐在电脑前就是谁，所以不要求登录 ——
     * 但它的对话也要存进数据库，就统一挂在这个内置账号下。
     *
     * <p>密码用一个随机 UUID 哈希后存起来，**没人知道它**：
     * 这个账号不是给 Web 登录用的，随机密码让它即便被猜到用户名也登不进来。
     */
    @Transactional
    public User findOrCreateLocalUser(String username) {
        return users.findByUsername(username).orElseGet(() -> {
            String unknowable = UUID.randomUUID().toString() + UUID.randomUUID();
            return users.save(new User(username, passwordEncoder.encode(unknowable), "本地终端用户"));
        });
    }
}
