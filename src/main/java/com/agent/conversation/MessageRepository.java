package com.agent.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** 按会话内的顺序读出全部消息，用于重建对话历史。 */
    List<Message> findByConversationIdOrderBySeqAsc(Long conversationId);

    /** 取当前最大序号，追加新消息时用来算下一个 seq。 */
    Optional<Message> findTopByConversationIdOrderBySeqDesc(Long conversationId);

    long countByConversationId(Long conversationId);

    /** 清空某个会话的消息（保留会话本身），对应 CLI 的 clear 语义。 */
    void deleteByConversationId(Long conversationId);
}
