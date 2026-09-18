package com.agent.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /** 某用户的全部会话，最近活跃的排前面。 */
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 按 id + 归属用户查会话。
     *
     * <p><b>这个方法的存在本身就是一道安全防线。</b> 所有「按 id 操作会话」的场景
     * 都必须走它，而不是 {@code findById} 之后再判断归属 —— 后者一旦忘记判断
     * 就是越权漏洞（任何登录用户改一下 URL 里的 id 就能读写别人的对话），
     * 而且这个漏洞**不报错、不崩溃，只是静默泄露**。
     *
     * <p>把用户条件放进查询里，就**从查询层不给越权的可能**。
     */
    Optional<Conversation> findByIdAndUserId(Long id, Long userId);

    List<Conversation> findAllByUserId(Long userId);
}
