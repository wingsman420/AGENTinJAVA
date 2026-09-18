package com.agent.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 测试用的 HTTP 客户端，打真实端口。
 *
 * <p><b>为什么不用 MockMvc / TestRestTemplate</b>：
 * <ul>
 *   <li>MockMvc 不经过真正的 Servlet 容器和 Spring Security 过滤器链，
 *       测不到"未登录被拦成 401""Cookie 有没有正确下发"这类关键行为</li>
 *   <li>Boot 4 把测试自动配置类拆到了独立构件（{@code @DataJpaTest} 就是例子），
 *       用那些注解还要先去确认坐标。JDK 自带 HttpClient 零依赖，更省事</li>
 * </ul>
 *
 * <p>它会**记住登录拿到的会话 Cookie** 并在后续请求里自动带上 ——
 * 这正是 Session 方案对客户端的要求，测试里把它显式做出来。
 */
public class ApiTestClient {

    private final String baseUrl;
    private final HttpClient http;
    private String sessionCookie;

    public ApiTestClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // 不自动跟随重定向：本 API 不该有重定向，出现了就说明哪里不对
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ---------- 请求 ----------

    public HttpResponse<String> post(String path, String json) throws Exception {
        return send(builder(path).POST(bodyOf(json)));
    }

    public HttpResponse<String> get(String path) throws Exception {
        return send(builder(path).GET());
    }

    /** PATCH 在 JDK HttpClient 里要通过 method(...) 指定。 */
    public HttpResponse<String> patch(String path, String json) throws Exception {
        return send(builder(path).method("PATCH", bodyOf(json)));
    }

    /** DELETE 允许带查询串，这里不需要 body。 */
    public HttpResponse<String> delete(String path) throws Exception {
        return send(builder(path).DELETE());
    }

    // ---------- 会话 ----------

    /** 登录并把下发的会话 Cookie 记住，后续请求自动带上。 */
    public HttpResponse<String> login(String username, String password) throws Exception {
        HttpResponse<String> response = post("/api/auth/login",
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
        response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID="))
                .findFirst()
                .ifPresent(value -> this.sessionCookie = value.split(";", 2)[0]);
        return response;
    }

    /** 模拟"未登录"：丢掉手上的 Cookie，但保留一个伪造值以便验证服务端不认。 */
    public void forgetSession() {
        this.sessionCookie = null;
    }

    public void useCookie(String cookie) {
        this.sessionCookie = cookie;
    }

    public String currentCookie() {
        return sessionCookie;
    }

    // ---------- 内部 ----------

    private HttpRequest.Builder builder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return builder;
    }

    private static HttpRequest.BodyPublisher bodyOf(String json) {
        return json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(
                builder.header("Content-Type", "application/json; charset=utf-8").build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
