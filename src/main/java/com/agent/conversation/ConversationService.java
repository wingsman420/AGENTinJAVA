package com.agent.conversation;

import com.agent.config.AgentProperties;
import com.agent.core.Agent;
import com.agent.core.ChatSession;
import com.agent.llm.model.ChatMessage;
import com.agent.user.User;
import com.agent.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 会话相关的业务逻辑：事务边界、归属校验、领域模型与实体之间的转换。
 *
 * <h2>关于事务边界（重要）</h2>
 * 发一条消息的流程被**刻意拆成三段**：
 *
 * <pre>
 *   ① 事务内：从库里读出会话历史，重建 ChatSession
 *   ② 事务外：调用大模型（可能耗时 10~30 秒）
 *   ③ 事务内：把新增的消息写回数据库
 * </pre>
 *
 * <p>为什么不整体包一个 {@code @Transactional}：大模型调用是**网络 I/O 且很慢**。
 * 如果把它放在事务里，那个事务会一直占着一条数据库连接 —— 并发几个请求就能把
 * 连接池（默认 10 条）耗尽，表现为"服务突然不响应了"，而根因却是一次 LLM 调用慢。
 * 事务要短，只包住真正的数据库操作。
 *
 * <p>也正因为要手动控制边界，这里用 {@link TransactionTemplate} 而不是
 * {@code @Transactional} 注解 —— 注解方式在本类内部自调用会失效（代理不生效），
 * 而拆成三段后必然存在自调用。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 会话标题的长度上限，取自首条用户消息。 */
    private static final int TITLE_MAX_LENGTH = 60;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final UserRepository users;
    private final Agent agent;
    private final AgentProperties props;
    private final TransactionTemplate tx;

    public ConversationService(ConversationRepository conversations,
                               MessageRepository messages,
                               UserRepository users,
                               Agent agent,
                               AgentProperties props,
                               PlatformTransactionManager transactionManager) {
        this.conversations = conversations;
        this.messages = messages;
        this.users = users;
        this.agent = agent;
        this.props = props;
        this.tx = new TransactionTemplate(transactionManager);

        // 工作目录现在可以由客户端指定，而它决定路径沙箱的根。
        // 没配白名单 = 登录用户能把沙箱根设到任意路径，进而读那台机器上任何目录。
        // 本地自用没问题，但必须让运维/使用者知道当前处于不设限状态。
        if (!props.hasAllowedRoots()) {
            log.warn("未配置 agent.allowed-roots，工作目录不设限 —— "
                    + "任何登录用户都可以把文件工具的访问范围指向本机任意路径。"
                    + "本地自用可以忽略；对外部署请务必配置白名单。");
        }
    }

    // ---------- CRUD ----------

    /**
     * 新建一个空会话，并把 system 消息一并落库（保证历史永远从 system 开头）。
     *
     * @param workspace 本会话的工作目录。文件工具只能访问它以内。
     *                  会先校验（存在、是目录、在白名单内）再写库 ——
     *                  非法路径不该被持久化，否则以后每次读这个会话都会失败
     */
    public ConversationView create(Long userId, String title, Path workspace) {
        return tx.execute(status -> {
            User user = requireUser(userId);
            Path normalized = validateWorkspace(workspace);
            String systemPrompt = agent.systemPrompt(normalized);

            Conversation conversation = conversations.save(
                    new Conversation(user, normalizeTitle(title), normalized.toString(), systemPrompt));

            messages.save(new Message(conversation, 1, "system", systemPrompt, null, null));
            return ConversationView.summary(conversation, 1);
        });
    }

    /**
     * 列出该用户用过的所有工作目录，最近活跃的在前。
     *
     * <p>"最近用过的目录"**不单独建表**，直接从会话里派生 —— 这样列表天然与
     * 实际有对话的地方一致，不会出现"列表里有、点进去空的"。
     * 代价是打开目录但一条对话都没建的不会出现在列表里（手动输一次即可，
     * 创建对话后就会记住）。
     */
    public List<WorkspaceView> listWorkspaces(Long userId) {
        return tx.execute(status -> conversations.listWorkspaces(userId));
    }

    /** 列出某个工作目录下的全部会话，最近活跃的在前。 */
    public List<ConversationView> listByWorkspace(Long userId, String workspace) {
        return tx.execute(status -> conversations
                .findByUserIdAndWorkspaceOrderByUpdatedAtDesc(userId, workspace).stream()
                .map(c -> ConversationView.summary(c, messages.countByConversationId(c.getId())))
                .toList());
    }

    public List<ConversationView> list(Long userId) {
        return tx.execute(status -> conversations.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(c -> ConversationView.summary(c, messages.countByConversationId(c.getId())))
                .toList());
    }

    public ConversationView get(Long userId, Long conversationId) {
        return tx.execute(status -> {
            Conversation conversation = requireOwned(userId, conversationId);
            List<MessageView> history = messages.findByConversationIdOrderBySeqAsc(conversationId)
                    .stream()
                    .map(MessageView::from)
                    .toList();
            return ConversationView.detail(conversation, history);
        });
    }

    public ConversationView rename(Long userId, Long conversationId, String newTitle) {
        return tx.execute(status -> {
            Conversation conversation = requireOwned(userId, conversationId);
            conversation.setTitle(normalizeTitle(newTitle));
            return ConversationView.summary(conversation, messages.countByConversationId(conversationId));
        });
    }

    /** 删除会话，连带删掉它的全部消息。 */
    public void delete(Long userId, Long conversationId) {
        tx.executeWithoutResult(status -> {
            Conversation conversation = requireOwned(userId, conversationId);
            messages.deleteByConversationId(conversationId);
            conversations.delete(conversation);
        });
    }

    /**
     * 清空会话的消息，但保留会话本身（对齐 CLI 的 clear 语义）。
     *
     * <p>清空后会**重新写入 system 消息** —— 不能把历史清成空表，否则下一轮
     * 模型收到的历史里没有 system 提示，会失去身份与行为约束。
     */
    public void clearMessages(Long userId, Long conversationId) {
        tx.executeWithoutResult(status -> {
            Conversation conversation = requireOwned(userId, conversationId);
            messages.deleteByConversationId(conversationId);

            // 必须 flush：Hibernate 在提交时会把 INSERT 排在 DELETE **之前**执行，
            // 于是"删掉旧消息、再插回 seq=1 的 system 消息"这个顺序里，
            // 新的 INSERT 会先跑，和还没删掉的旧 seq=1 撞上 (conversation_id, seq)
            // 唯一约束。手动 flush 把 DELETE 先推到数据库，顺序才是对的。
            messages.flush();

            messages.save(new Message(conversation, 1, "system",
                    conversation.getSystemPrompt(), null, null));
            conversation.touch();
        });
    }

    // ---------- 对话 ----------

    /**
     * 发一条消息，返回模型的回答。
     *
     * <p>三段式事务边界，理由见类注释。
     */
    public ChatResult chat(Long userId, Long conversationId, String input) {
        // ① 事务内：读历史
        Loaded loaded = tx.execute(status -> {
            Conversation conversation = requireOwned(userId, conversationId);
            List<ChatMessage> history = messages.findByConversationIdOrderBySeqAsc(conversationId)
                    .stream()
                    .map(Message::toChatMessage)
                    .toList();
            ChatSession session = ChatSession.restore(
                    String.valueOf(conversationId), conversation.getSystemPrompt(),
                    Path.of(conversation.getWorkspace()), history);
            return new Loaded(conversation.getId(), session, session.size());
        });

        ChatSession session = loaded.session();
        int beforeCount = loaded.messageCount();

        // ② 事务外：调模型。这一步可能耗时几十秒，绝不能占着数据库连接。
        String reply = agent.chat(session, input);

        // ③ 事务内：只把新增的消息落库
        int finalHistorySize = tx.execute(status -> {
            Conversation conversation = requireOwned(userId, conversationId);
            persistFrom(conversation, session, beforeCount);
            conversation.touch();
            return session.size();
        });

        return new ChatResult(loaded.conversationId(), reply, finalHistorySize);
    }

    /** 加载会话（供 CLI 直接使用，不经过三段式流程时）。 */
    public ChatSession loadSession(Long userId, Long conversationId) {
        return tx.execute(status -> {
            Conversation conversation = requireOwned(userId, conversationId);
            List<ChatMessage> history = messages.findByConversationIdOrderBySeqAsc(conversationId)
                    .stream()
                    .map(Message::toChatMessage)
                    .toList();
            return ChatSession.restore(
                    String.valueOf(conversationId), conversation.getSystemPrompt(),
                    Path.of(conversation.getWorkspace()), history);
        });
    }

    /** 事务外调完模型后，把新增消息落库。 */
    public void persistNewMessages(Long userId, Long conversationId, ChatSession session, int fromIndex) {
        tx.executeWithoutResult(status -> {
            Conversation conversation = requireOwned(userId, conversationId);
            persistFrom(conversation, session, fromIndex);
            conversation.touch();
        });
    }

    // ---------- 内部 ----------

    /**
     * 把 {@code session} 里从 {@code fromIndex} 开始的消息追加到数据库。
     *
     * <p>只写新增的部分，而不是每轮全量重写整个历史 —— 后者在长对话里开销会越来越大。
     */
    private void persistFrom(Conversation conversation, ChatSession session, int fromIndex) {
        List<ChatMessage> all = session.messages();
        int nextSeq = messages.findTopByConversationIdOrderBySeqDesc(conversation.getId())
                .map(m -> m.getSeq() + 1)
                .orElse(1);

        for (int i = Math.max(fromIndex, 0); i < all.size(); i++) {
            messages.save(Message.from(conversation, nextSeq++, all.get(i)));
        }
    }

    /**
     * 取会话并校验归属。
     *
     * <p>查不到和"不属于你"返回同一个错误 —— 对调用方而言这两种情况没有区别，
     * 而且区分它们会泄露"这个 id 确实存在"的信息。
     */
    private Conversation requireOwned(Long userId, Long conversationId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new NoSuchElementException(
                        "会话不存在或不属于当前用户: " + conversationId));
    }

    private User requireUser(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("用户不存在: " + userId));
    }

    /**
     * 校验并规范化工作目录。
     *
     * <p>非法路径**必须在写库之前拦下** —— 一旦存进去，之后每次读这个会话都会失败，
     * 而且用户会在一个自己打不开的会话里反复遇到错误，很难自己定位到原因。
     *
     * <p>三道检查：存在、是目录、在白名单内。前两条是"路径本身可用"，
     * 第三条是"这个路径允许被这个用户当作沙箱根"。
     */
    private Path validateWorkspace(Path workspace) {
        // 不指定就用配置里的默认工作目录。
        // 这样做是为了**兼容旧客户端** —— 之前建会话只需要 title，
        // 把 workspace 设计成必填会让所有既有调用直接 400。
        Path target = (workspace == null) ? props.workspace() : workspace;
        Path normalized = target.toAbsolutePath().normalize();

        if (!Files.exists(normalized)) {
            throw new IllegalArgumentException("工作目录不存在：" + normalized);
        }
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("工作目录不是一个目录：" + normalized);
        }
        if (!Files.isReadable(normalized)) {
            throw new IllegalArgumentException("工作目录不可读：" + normalized);
        }
        if (!props.isWorkspaceAllowed(normalized)) {
            throw new IllegalArgumentException(
                    "工作目录不在允许的范围内：" + normalized + "（见配置 agent.allowed-roots）");
        }
        return normalized;
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "新会话";
        }
        String trimmed = title.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, TITLE_MAX_LENGTH);
    }

    /** ① 与 ② 之间的载体。 */
    private record Loaded(Long conversationId, ChatSession session, int messageCount) {
    }

    /** 发消息的结果。 */
    public record ChatResult(Long conversationId, String reply, int historySize) {
    }
}
