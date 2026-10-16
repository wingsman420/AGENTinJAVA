package com.agent.conversation;

import com.agent.llm.FakeLlmClient;
import com.agent.llm.LlmClient;
import com.agent.user.User;
import com.agent.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 会话服务的测试。
 *
 * <p>这里最关键的一组用例是**越权访问**。会话按 id 操作，如果查询时不带归属用户条件，
 * 任何登录用户改一下 URL 里的 id 就能读写别人的对话 —— 而且这个漏洞
 * **不报错、不崩溃，只是静默泄露数据**，靠人工点几下页面是发现不了的。
 */
@SpringBootTest(properties = {
        "agent.cli.enabled=false",
        "agent.api-key=test-key"
})
@ActiveProfiles("test")
class ConversationServiceTest {

    @MockitoBean
    private LlmClient llmClient;

    @Autowired
    private ConversationService service;

    @Autowired
    private UserRepository users;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private MessageRepository messages;

    private User alice;
    private User bob;

    /** 这些用例不验证目录隔离，统一用启动目录当工作目录即可。 */
    private static final Path WORKSPACE = Path.of(System.getProperty("user.dir"));

    /** 两个不同的工作目录，用来验证会话按目录隔离。 */
    @TempDir
    Path workspaceA;

    @TempDir
    Path workspaceB;

    @BeforeEach
    void setUp() {
        // @SpringBootTest **默认不回滚**（不像 @DataJpaTest 自带 @Transactional），
        // 而 H2 内存库在同一个 JVM 里跨测试方法持久存在 —— 不清库的话，
        // 第二个测试再插一次 alice 就会撞 username 的唯一约束。
        // 删除顺序要照顾外键：messages → conversations → users。
        messages.deleteAll();
        conversations.deleteAll();
        users.deleteAll();

        // 用 @MockitoBean 把真实的 DeepSeekClient 换掉，测试不联网。
        // 这里只需要"一问一答"的固定响应，所以直接 stub 即可；
        // 需要脚本化多轮工具调用的场景由 AgentTest 用 FakeLlmClient 覆盖。
        // FakeLlmClient.text(...) 是现成的响应构造器，复用它保持两处一致。
        when(llmClient.chat(any())).thenReturn(FakeLlmClient.text("收到，这是回答。"));

        alice = users.save(new User("alice", "hash-a", "Alice"));
        bob = users.save(new User("bob", "hash-b", "Bob"));
    }

    // ---------- 创建 ----------

    @Test
    void 新建会话会一并写入system消息() {
        ConversationView created = service.create(alice.getId(), "第一个会话", WORKSPACE);

        assertThat(created.title()).isEqualTo("第一个会话");
        assertThat(created.messageCount()).isEqualTo(1);

        // system 消息必须在，否则下一轮模型会失去身份与行为约束
        assertThat(service.get(alice.getId(), created.id()).messages())
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.role()).isEqualTo("system");
                    assertThat(m.seq()).isEqualTo(1);
                });
    }

    @Test
    void 标题为空时给默认值() {
        assertThat(service.create(alice.getId(), "   ", WORKSPACE).title()).isEqualTo("新会话");
    }

    @Test
    void 过长的标题会被截断() {
        String longTitle = "标".repeat(200);

        assertThat(service.create(alice.getId(), longTitle, WORKSPACE).title()).hasSize(60);
    }

    // ---------- 列表 ----------

    @Test
    void 列表只返回自己的会话() {
        service.create(alice.getId(), "alice 的会话", WORKSPACE);
        service.create(bob.getId(), "bob 的会话", WORKSPACE);

        assertThat(service.list(alice.getId()))
                .extracting(ConversationView::title)
                .containsExactly("alice 的会话");
    }

    // ---------- 越权（本类的核心） ----------

    @Test
    void 读别人的会话会被拒绝() {
        ConversationView aliceConversation = service.create(alice.getId(), "alice 的会话", WORKSPACE);

        assertThatThrownBy(() -> service.get(bob.getId(), aliceConversation.id()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 改别人会话的标题会被拒绝() {
        ConversationView aliceConversation = service.create(alice.getId(), "原标题", WORKSPACE);

        assertThatThrownBy(() -> service.rename(bob.getId(), aliceConversation.id(), "被篡改"))
                .isInstanceOf(NoSuchElementException.class);

        // 确认原会话没被改动
        assertThat(service.get(alice.getId(), aliceConversation.id()).title()).isEqualTo("原标题");
    }

    @Test
    void 删别人的会话会被拒绝() {
        ConversationView aliceConversation = service.create(alice.getId(), "alice 的会话", WORKSPACE);

        assertThatThrownBy(() -> service.delete(bob.getId(), aliceConversation.id()))
                .isInstanceOf(NoSuchElementException.class);

        // 确认会话还在
        assertThat(service.list(alice.getId())).hasSize(1);
    }

    @Test
    void 清空别人会话的消息会被拒绝() {
        ConversationView aliceConversation = service.create(alice.getId(), "alice 的会话", WORKSPACE);

        assertThatThrownBy(() -> service.clearMessages(bob.getId(), aliceConversation.id()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 往别人会话里发消息会被拒绝() {
        ConversationView aliceConversation = service.create(alice.getId(), "alice 的会话", WORKSPACE);

        assertThatThrownBy(() -> service.chat(bob.getId(), aliceConversation.id(), "偷看"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ---------- 改 / 删 ----------

    @Test
    void 改标题生效() {
        ConversationView created = service.create(alice.getId(), "旧标题", WORKSPACE);

        ConversationView renamed = service.rename(alice.getId(), created.id(), "新标题");

        assertThat(renamed.title()).isEqualTo("新标题");
        assertThat(service.get(alice.getId(), created.id()).title()).isEqualTo("新标题");
    }

    @Test
    void 删会话会连带删掉它的消息() {
        ConversationView created = service.create(alice.getId(), "待删会话", WORKSPACE);
        Long id = created.id();
        assertThat(messages.countByConversationId(id)).isEqualTo(1);

        service.delete(alice.getId(), id);

        assertThat(service.list(alice.getId())).isEmpty();
        assertThat(messages.countByConversationId(id)).isZero();
    }

    @Test
    void 清空消息会保留会话与system消息() {
        ConversationView created = service.create(alice.getId(), "要清空的会话", WORKSPACE);
        Long id = created.id();

        service.clearMessages(alice.getId(), id);

        // 会话本身还在
        ConversationView after = service.get(alice.getId(), id);
        // 消息被清空，但重新写回了一条 system
        assertThat(after.messages()).singleElement()
                .satisfies(m -> assertThat(m.role()).isEqualTo("system"));
    }

    // ---------- 工作目录（本次新增的核心能力） ----------

    @Test
    void 不同工作目录下的会话互相隔离() {
        service.create(alice.getId(), "项目 A 的会话", workspaceA);
        service.create(alice.getId(), "项目 B 的会话", workspaceB);

        assertThat(service.listByWorkspace(alice.getId(), workspaceA.toString()))
                .extracting(ConversationView::title)
                .containsExactly("项目 A 的会话");
        assertThat(service.listByWorkspace(alice.getId(), workspaceB.toString()))
                .extracting(ConversationView::title)
                .containsExactly("项目 B 的会话");
    }

    @Test
    void 会话会记住创建时的工作目录() {
        ConversationView created = service.create(alice.getId(), "带目录的会话", workspaceA);

        assertThat(created.workspace()).isEqualTo(workspaceA.toString());
    }

    @Test
    void 同一目录的不同路径写法应当查到同一批会话() {
        service.create(alice.getId(), "会话", workspaceA);

        // 这是真实踩过的坑：查询是字符串精确匹配，而同一目录有多种合法写法
        // （正/反斜杠、大小写、尾部斜杠）。写法不一致就会被当成两个目录，
        // 表现为"目录列表显示有对话，点进去却是空的" —— 不报错，只是静默对不上。
        String forwardSlashes = workspaceA.toString().replace('\\', '/');
        assertThat(service.listByWorkspace(alice.getId(), forwardSlashes))
                .extracting(ConversationView::title)
                .containsExactly("会话");

        String withTrailingSeparator = workspaceA + java.io.File.separator;
        assertThat(service.listByWorkspace(alice.getId(), withTrailingSeparator))
                .extracting(ConversationView::title)
                .containsExactly("会话");
    }

    @Test
    void 列出用过的工作目录并带上会话数() {
        service.create(alice.getId(), "A 里第一个", workspaceA);
        service.create(alice.getId(), "A 里第二个", workspaceA);
        service.create(alice.getId(), "B 里第一个", workspaceB);

        assertThat(service.listWorkspaces(alice.getId()))
                .extracting(WorkspaceView::path, WorkspaceView::conversationCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(workspaceA.toString(), 2L),
                        org.assertj.core.groups.Tuple.tuple(workspaceB.toString(), 1L));
    }

    @Test
    void 别人的工作目录不会出现在我的列表里() {
        service.create(alice.getId(), "alice 的", workspaceA);
        service.create(bob.getId(), "bob 的", workspaceB);

        assertThat(service.listWorkspaces(alice.getId()))
                .extracting(WorkspaceView::path)
                .containsExactly(workspaceA.toString());
    }

    @Test
    void 工作目录不存在时拒绝创建() {
        Path missing = workspaceA.resolve("没有这个目录");

        // 非法路径必须在写库之前拦下 —— 存进去之后每次打开这个会话都会失败
        assertThatThrownBy(() -> service.create(alice.getId(), "x", missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 工作目录是文件时拒绝创建() throws Exception {
        Path aFile = workspaceA.resolve("a.txt");
        java.nio.file.Files.writeString(aFile, "x");

        assertThatThrownBy(() -> service.create(alice.getId(), "x", aFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是一个目录");
    }

    @Test
    void 不传工作目录时回落到默认值() {
        // 兼容旧客户端：之前建会话只需要 title
        ConversationView created = service.create(alice.getId(), "用默认目录", null);

        assertThat(created.workspace()).isNotBlank();
    }

    // ---------- 对话与持久化 ----------

    @Test
    void 发消息会把新增的消息落库() {
        ConversationView created = service.create(alice.getId(), "对话测试", WORKSPACE);

        ConversationService.ChatResult result = service.chat(alice.getId(), created.id(), "你好");

        assertThat(result.reply()).isNotBlank();
        // system(建会话时) + user + assistant
        assertThat(result.historySize()).isEqualTo(3);
        assertThat(service.get(alice.getId(), created.id()).messages())
                .extracting(MessageView::role)
                .containsExactly("system", "user", "assistant");
    }

    @Test
    void 历史能从数据库完整重建并在重启后继续对话() {
        ConversationView created = service.create(alice.getId(), "多轮对话", WORKSPACE);

        service.chat(alice.getId(), created.id(), "第一个问题");
        // 每一次 chat 都从库里重新读历史，等价于"进程重启后再来一轮"
        service.chat(alice.getId(), created.id(), "第二个问题");

        assertThat(service.get(alice.getId(), created.id()).messages())
                .extracting(MessageView::content)
                .contains("第一个问题", "第二个问题");
    }

    @Test
    void 从数据库重建的会话带上正确的system提示() {
        ConversationView created = service.create(alice.getId(), "重建测试", WORKSPACE);

        var session = service.loadSession(alice.getId(), created.id());

        assertThat(session.messages()).isNotEmpty();
        assertThat(session.messages().get(0).role()).isEqualTo("system");
        assertThat(session.systemPrompt()).isNotBlank();
    }
}
