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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鉴权流程的端到端测试。
 *
 * <p><b>为什么用 JDK 自带的 HttpClient 打真实端口，而不是 MockMvc</b>：
 * <ul>
 *   <li>MockMvc 不经过真正的 Servlet 容器和 Spring Security 过滤器链，
 *       测不到"未登录被拦成 401""Cookie 有没有正确下发"这类关键行为</li>
 *   <li>Boot 4 把测试自动配置类拆到了独立构件（{@code @DataJpaTest} 就是例子），
 *       用 {@code TestRestTemplate}/{@code @AutoConfigureMockMvc} 还要先确认坐标。
 *       JDK 自带 HttpClient 零依赖，反而更省事</li>
 * </ul>
 *
 * <p>Cookie 需要手动从 {@code Set-Cookie} 取出来再带回去 —— 正好把
 * "Session 方案客户端要做什么"演示清楚了。
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

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeEach
    void setUp() {
        messages.deleteAll();
        conversations.deleteAll();
        users.deleteAll();
    }

    // ---------- 正常流程 ----------

    @Test
    void 注册后能登录并用拿到的会话访问受保护接口() throws Exception {
        assertThat(post("/api/auth/register",
                "{\"username\":\"alice\",\"password\":\"secret123\"}").statusCode())
                .isEqualTo(201);

        HttpResponse<String> login = post("/api/auth/login",
                "{\"username\":\"alice\",\"password\":\"secret123\"}");
        assertThat(login.statusCode()).isEqualTo(200);

        String cookie = sessionCookieOf(login);
        assertThat(cookie).as("登录成功必须下发会话 Cookie").isNotBlank();

        HttpResponse<String> me = get("/api/me", cookie);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("alice");
    }

    @Test
    void 会话Cookie带上了安全属性() throws Exception {
        post("/api/auth/register", "{\"username\":\"bob\",\"password\":\"secret123\"}");
        HttpResponse<String> login = post("/api/auth/login",
                "{\"username\":\"bob\",\"password\":\"secret123\"}");

        String setCookie = login.headers().firstValue("Set-Cookie").orElse("");
        // HttpOnly：JS 读不到，XSS 偷不走会话
        assertThat(setCookie).containsIgnoringCase("HttpOnly");
        // SameSite=Strict：跨站请求不会带上它，这是本项目敢关掉 CSRF 的依据
        assertThat(setCookie.toLowerCase()).contains("samesite=strict");
    }

    // ---------- 未登录 ----------

    @Test
    void 未登录访问受保护接口返回401() throws Exception {
        HttpResponse<String> response = get("/api/me", null);

        // 必须是 401 而不是 403 —— 客户端靠这个区分"该去登录"和"登录了但没权限"
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("未登录");
    }

    @Test
    void 用伪造的会话Cookie访问仍是401() throws Exception {
        // HTTP 头只能是 ASCII，这里不能用中文（会抛 invalid header value）
        HttpResponse<String> response = get("/api/me", "JSESSIONID=forged-session-id-123456");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ---------- 登录失败 ----------

    @Test
    void 密码错误返回401且提示不泄露用户是否存在() throws Exception {
        post("/api/auth/register", "{\"username\":\"carol\",\"password\":\"secret123\"}");

        HttpResponse<String> wrongPassword = post("/api/auth/login",
                "{\"username\":\"carol\",\"password\":\"wrong-password\"}");
        HttpResponse<String> noSuchUser = post("/api/auth/login",
                "{\"username\":\"nobody\",\"password\":\"secret123\"}");

        // 两者必须返回**完全一致**的响应 —— 区分开来等于给攻击者一个用户名枚举接口
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(noSuchUser.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.body()).isEqualTo(noSuchUser.body());
    }

    // ---------- 注册校验 ----------

    @Test
    void 重复用户名注册返回400() throws Exception {
        post("/api/auth/register", "{\"username\":\"dave\",\"password\":\"secret123\"}");

        HttpResponse<String> again = post("/api/auth/register",
                "{\"username\":\"dave\",\"password\":\"another123\"}");

        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(again.body()).contains("已被占用");
    }

    @Test
    void 过短的密码被拒绝() throws Exception {
        HttpResponse<String> response = post("/api/auth/register",
                "{\"username\":\"eve\",\"password\":\"123\"}");

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void 密码不会以明文形式出现在响应里() throws Exception {
        String body = post("/api/auth/register",
                "{\"username\":\"frank\",\"password\":\"secret123\"}").body();

        assertThat(body).doesNotContain("secret123").contains("frank");
    }

    @Test
    void 注销后会话失效() throws Exception {
        post("/api/auth/register", "{\"username\":\"grace\",\"password\":\"secret123\"}");
        String cookie = sessionCookieOf(post("/api/auth/login",
                "{\"username\":\"grace\",\"password\":\"secret123\"}"));

        postWithCookie("/api/auth/logout", cookie);

        assertThat(get("/api/me", cookie).statusCode()).isEqualTo(401);
    }

    // ---------- 辅助 ----------

    private HttpResponse<String> post(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                .build());
    }

    private HttpResponse<String> postWithCookie(String path, String cookie) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(base() + path)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return send(builder.build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private String base() {
        return "http://localhost:" + port;
    }

    /** 从 Set-Cookie 里取出 JSESSIONID=xxx 这一段（后续请求要原样带回去）。 */
    private static String sessionCookieOf(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID="))
                .map(value -> value.split(";", 2)[0])
                .findFirst()
                .orElse("");
    }
}
