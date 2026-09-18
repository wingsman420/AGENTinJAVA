package com.agent.web;

import com.agent.conversation.ConversationRepository;
import com.agent.conversation.MessageRepository;
import com.agent.llm.FakeLlmClient;
import com.agent.llm.LlmClient;
import com.agent.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 会话接口的端到端测试：CRUD、对话、以及**归属隔离**。
 *
 * <p>归属隔离是本类的重点。会话实体是按 id 操作的，如果查询时不带用户条件，
 * 任何登录用户改一下 URL 里的 id 就能读写别人的对话 ——
 * 这个漏洞**不报错、不崩溃，只是静默泄露**，靠人工点页面根本发现不了。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent.cli.enabled=false", "agent.api-key=test-key"})
@ActiveProfiles("test")
class ConversationApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private LlmClient llmClient;

    @Autowired
    private UserRepository users;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private MessageRepository messages;

    private ApiTestClient alice;
    private ApiTestClient bob;

    @BeforeEach
    void setUp() throws Exception {
        messages.deleteAll();
        conversations.deleteAll();
        users.deleteAll();

        // 用 Mockito 假客户端，测试不联网。
        // FakeLlmClient.text(...) 是现成的响应构造器，复用它保持两处一致。
        when(llmClient.chat(any())).thenReturn(FakeLlmClient.text("收到，这是回答。"));

        alice = registerAndLogin("alice");
        bob = registerAndLogin("bob");
    }

    // ---------- 完整链路 ----------

    @Test
    void 建会话_发消息_读历史的完整链路() throws Exception {
        HttpResponse<String> created = alice.post("/api/conversations",
                "{\"title\":\"第一个会话\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        Long conversationId = idOf(created);

        HttpResponse<String> chat = alice.post("/api/chat",
                "{\"conversationId\":%d,\"message\":\"你好\"}".formatted(conversationId));
        assertThat(chat.statusCode()).isEqualTo(200);
        assertThat(chat.body()).contains("收到，这是回答。");

        HttpResponse<String> detail = alice.get("/api/conversations/" + conversationId);
        assertThat(detail.statusCode()).isEqualTo(200);
        // system（建会话时写入）+ user + assistant
        assertThat(detail.body())
                .contains("\"role\":\"system\"")
                .contains("\"role\":\"user\"")
                .contains("\"role\":\"assistant\"")
                .contains("你好");
    }

    @Test
    void 历史在多次对话后累积() throws Exception {
        Long id = idOf(alice.post("/api/conversations", "{\"title\":\"多轮\"}"));

        alice.post("/api/chat", "{\"conversationId\":%d,\"message\":\"第一个问题\"}".formatted(id));
        alice.post("/api/chat", "{\"conversationId\":%d,\"message\":\"第二个问题\"}".formatted(id));

        assertThat(alice.get("/api/conversations/" + id).body())
                .contains("第一个问题")
                .contains("第二个问题");
    }

    // ---------- 列表与 CRUD ----------

    @Test
    void 列表只返回自己的会话() throws Exception {
        alice.post("/api/conversations", "{\"title\":\"alice 的\"}");
        bob.post("/api/conversations", "{\"title\":\"bob 的\"}");

        assertThat(alice.get("/api/conversations").body())
                .contains("alice 的")
                .doesNotContain("bob 的");
    }

    @Test
    void 改标题生效() throws Exception {
        Long id = idOf(alice.post("/api/conversations", "{\"title\":\"旧标题\"}"));

        assertThat(alice.patch("/api/conversations/" + id, "{\"title\":\"新标题\"}").statusCode())
                .isEqualTo(200);
        assertThat(alice.get("/api/conversations/" + id).body()).contains("新标题");
    }

    @Test
    void 删会话后查不到() throws Exception {
        Long id = idOf(alice.post("/api/conversations", "{\"title\":\"待删\"}"));

        assertThat(alice.delete("/api/conversations/" + id).statusCode()).isEqualTo(200);

        assertThat(alice.get("/api/conversations/" + id).statusCode()).isEqualTo(404);
        assertThat(alice.get("/api/conversations").body()).doesNotContain("待删");
    }

    @Test
    void 清空消息会保留会话与system消息() throws Exception {
        Long id = idOf(alice.post("/api/conversations", "{\"title\":\"要清空\"}"));
        alice.post("/api/chat", "{\"conversationId\":%d,\"message\":\"会消失\"}".formatted(id));

        assertThat(alice.delete("/api/conversations/" + id + "/messages").statusCode())
                .isEqualTo(200);

        HttpResponse<String> detail = alice.get("/api/conversations/" + id);
        assertThat(detail.statusCode()).isEqualTo(200);          // 会话还在
        assertThat(detail.body()).contains("\"role\":\"system\"")  // system 被写回
                .doesNotContain("会消失");
    }

    // ---------- 归属隔离（本类的核心） ----------

    @Test
    void 读别人的会话返回404() throws Exception {
        Long aliceConversation = idOf(alice.post("/api/conversations", "{\"title\":\"alice 的\"}"));

        HttpResponse<String> response = bob.get("/api/conversations/" + aliceConversation);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("alice 的");
    }

    @Test
    void 往别人的会话发消息返回404() throws Exception {
        Long aliceConversation = idOf(alice.post("/api/conversations", "{\"title\":\"alice 的\"}"));

        HttpResponse<String> response = bob.post("/api/chat",
                "{\"conversationId\":%d,\"message\":\"偷看\"}".formatted(aliceConversation));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void 改别人会话的标题返回404且原值不变() throws Exception {
        Long aliceConversation = idOf(alice.post("/api/conversations", "{\"title\":\"原标题\"}"));

        assertThat(bob.patch("/api/conversations/" + aliceConversation,
                "{\"title\":\"被篡改\"}").statusCode()).isEqualTo(404);

        assertThat(alice.get("/api/conversations/" + aliceConversation).body()).contains("原标题");
    }

    @Test
    void 删别人的会话返回404且会话仍在() throws Exception {
        Long aliceConversation = idOf(alice.post("/api/conversations", "{\"title\":\"不能被删\"}"));

        assertThat(bob.delete("/api/conversations/" + aliceConversation).statusCode())
                .isEqualTo(404);

        assertThat(alice.get("/api/conversations/" + aliceConversation).statusCode()).isEqualTo(200);
    }

    @Test
    void 清空别人会话的消息返回404() throws Exception {
        Long aliceConversation = idOf(alice.post("/api/conversations", "{\"title\":\"alice 的\"}"));

        assertThat(bob.delete("/api/conversations/" + aliceConversation + "/messages").statusCode())
                .isEqualTo(404);
    }

    // ---------- 未登录 ----------

    @Test
    void 未登录访问会话接口返回401() throws Exception {
        ApiTestClient anonymous = new ApiTestClient(port);

        assertThat(anonymous.get("/api/conversations").statusCode()).isEqualTo(401);
        assertThat(anonymous.post("/api/conversations", "{\"title\":\"x\"}").statusCode())
                .isEqualTo(401);
        assertThat(anonymous.post("/api/chat",
                "{\"conversationId\":1,\"message\":\"x\"}").statusCode()).isEqualTo(401);
    }

    // ---------- 参数校验 ----------

    @Test
    void 缺少conversationId返回400() throws Exception {
        assertThat(alice.post("/api/chat", "{\"message\":\"你好\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void 缺少message返回400() throws Exception {
        Long id = idOf(alice.post("/api/conversations", "{\"title\":\"x\"}"));

        assertThat(alice.post("/api/chat",
                "{\"conversationId\":%d}".formatted(id)).statusCode()).isEqualTo(400);
    }

    @Test
    void 会话不存在时发消息返回404() throws Exception {
        assertThat(alice.post("/api/chat",
                "{\"conversationId\":999999,\"message\":\"你好\"}").statusCode())
                .isEqualTo(404);
    }

    // ---------- 状态接口 ----------

    @Test
    void status接口带上当前用户与会话数() throws Exception {
        alice.post("/api/conversations", "{\"title\":\"一个\"}");

        HttpResponse<String> response = alice.get("/api/status");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("alice").contains("\"conversations\":1");
    }

    // ---------- 辅助 ----------

    private ApiTestClient registerAndLogin(String username) throws Exception {
        ApiTestClient client = new ApiTestClient(port);
        client.post("/api/auth/register",
                "{\"username\":\"%s\",\"password\":\"secret123\"}".formatted(username));
        client.login(username, "secret123");
        return client;
    }

    private static Long idOf(HttpResponse<String> response) {
        return JSON.readTree(response.body()).path("id").asLong();
    }
}
