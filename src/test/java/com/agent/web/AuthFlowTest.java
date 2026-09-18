package com.agent.web;

import com.agent.conversation.ConversationRepository;
import com.agent.conversation.MessageRepository;
import com.agent.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权流程的端到端测试：注册、登录、会话下发、未登录拦截。
 *
 * <p>打的是**真实端口**，所以走完了完整的 Servlet 容器 + Spring Security 过滤器链 ——
 * 这是 MockMvc 测不到的层次。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent.cli.enabled=false", "agent.api-key=test-key"})
@ActiveProfiles("test")
class AuthFlowTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private MessageRepository messages;

    private ApiTestClient client;

    @BeforeEach
    void setUp() {
        messages.deleteAll();
        conversations.deleteAll();
        users.deleteAll();
        client = new ApiTestClient(port);
    }

    // ---------- 正常流程 ----------

    @Test
    void 注册后能登录并用拿到的会话访问受保护接口() throws Exception {
        assertThat(client.post("/api/auth/register",
                "{\"username\":\"alice\",\"password\":\"secret123\"}").statusCode())
                .isEqualTo(201);

        HttpResponse<String> login = client.login("alice", "secret123");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(client.currentCookie()).as("登录成功必须下发会话 Cookie").isNotBlank();

        HttpResponse<String> me = client.get("/api/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("alice");
    }

    @Test
    void 会话Cookie带上安全属性() throws Exception {
        client.post("/api/auth/register", "{\"username\":\"bob\",\"password\":\"secret123\"}");
        HttpResponse<String> login = client.login("bob", "secret123");

        String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
        // HttpOnly：JS 读不到，XSS 偷不走会话
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        // SameSite=Strict：跨站请求不会带上它，这是本项目敢关掉 CSRF 的依据
        assertThat(setCookie.toLowerCase()).contains("samesite=strict");
    }

    // ---------- 未登录 ----------

    @Test
    void 未登录访问受保护接口返回401() throws Exception {
        HttpResponse<String> response = client.get("/api/me");

        // 必须是 401 而不是 403 —— 客户端靠这个区分"该去登录"和"登录了但没权限"
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("未登录");
    }

    @Test
    void 用伪造的会话Cookie访问仍是401() throws Exception {
        // HTTP 头只能是 ASCII，这里不能用中文
        client.useCookie("JSESSIONID=forged-session-id-123456");

        assertThat(client.get("/api/me").statusCode()).isEqualTo(401);
    }

    // ---------- 登录失败 ----------

    @Test
    void 密码错误返回401且提示不泄露用户是否存在() throws Exception {
        client.post("/api/auth/register", "{\"username\":\"carol\",\"password\":\"secret123\"}");

        HttpResponse<String> wrongPassword = client.post("/api/auth/login",
                "{\"username\":\"carol\",\"password\":\"wrong-password\"}");
        HttpResponse<String> noSuchUser = client.post("/api/auth/login",
                "{\"username\":\"nobody\",\"password\":\"secret123\"}");

        // 两者必须返回**完全一致**的响应 —— 区分开来等于给攻击者一个用户名枚举接口
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(noSuchUser.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.body()).isEqualTo(noSuchUser.body());
    }

    // ---------- 注册校验 ----------

    @Test
    void 重复用户名注册返回400() throws Exception {
        client.post("/api/auth/register", "{\"username\":\"dave\",\"password\":\"secret123\"}");

        HttpResponse<String> again = client.post("/api/auth/register",
                "{\"username\":\"dave\",\"password\":\"another123\"}");

        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(again.body()).contains("已被占用");
    }

    @Test
    void 过短的密码被拒绝() throws Exception {
        HttpResponse<String> response = client.post("/api/auth/register",
                "{\"username\":\"eve\",\"password\":\"123\"}");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void 密码不会以明文形式出现在响应里() throws Exception {
        String body = client.post("/api/auth/register",
                "{\"username\":\"frank\",\"password\":\"secret123\"}").body();

        assertThat(body).doesNotContain("secret123").contains("frank");
    }

    @Test
    void 请求体不是合法JSON时返回400而不是500() throws Exception {
        HttpResponse<String> response = client.post("/api/auth/register", "{这不是 JSON");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void 注销后会话失效() throws Exception {
        client.post("/api/auth/register", "{\"username\":\"grace\",\"password\":\"secret123\"}");
        client.login("grace", "secret123");
        assertThat(client.get("/api/me").statusCode()).isEqualTo(200);

        client.post("/api/auth/logout", null);

        assertThat(client.get("/api/me").statusCode()).isEqualTo(401);
    }
}
