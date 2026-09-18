package com.agent.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;

/**
 * Spring Security 配置。
 *
 * <p><b>注意 Spring Security 7 的写法差异</b>：{@code HttpSecurity} 已经**删除了**
 * {@code and()} 和 {@code authorizeRequests}，只能写 lambda DSL。照搬 Security 6
 * 的链式写法会直接编译不过。
 */
@Configuration
public class SecurityConfig {

    /**
     * 过滤链只在 Web 模式下需要。
     *
     * <p>加 {@code @ConditionalOnWebApplication} 是为了支持"纯终端模式"
     * （{@code --spring.main.web-application-type=none}）：那种情况下没有 Servlet
     * 容器，这些 Bean 既用不上、也创建不出来 —— 见下面 {@code authenticationManager}
     * 那条注释里解释的原因。
     */
    @Bean
    @ConditionalOnWebApplication
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF：这里**刻意关闭**。
                //
                // 权衡说明（不是疏忽）：CSRF 防护针对的是"浏览器带着 Cookie 自动发起
                // 跨站请求"这一场景。本项目是纯 JSON API，客户端以 curl / PowerShell 为主，
                // 开启后每个请求都要额外带 token，摩擦很大。
                //
                // 作为补偿，会话 Cookie 设成了 SameSite=Strict（见 application.yml）——
                // 浏览器不会在跨站请求里带上它，这在很大程度上覆盖了 CSRF 的主要风险面。
                // 如果将来要加浏览器前端，应该改回 CookieCsrfTokenRepository。
                .csrf(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        // 注册和登录必须放行，否则没法登录
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // 其余 API 一律要求登录
                        .requestMatchers("/api/**").authenticated()
                        // 其它（错误页、静态资源等）不管，没有可保护的内容
                        .anyRequest().permitAll())

                // 会话策略：按需创建。登录成功后由 AuthController 显式写回会话。
                // 顺带一提，Spring Security 默认会在登录成功时更换 session id，
                // 这是防"会话固定攻击"的关键一步，不要关掉。
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                // 未登录/无权限时返回 JSON + 正确的状态码。
                // 不配的话默认是 403，而"没登录"语义上应该是 401 —— 客户端要靠这个
                // 区分"该去登录"和"登录了但没权限"。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "未登录或会话已过期"))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                        "无权访问该资源")))

                // 不用表单登录和 HTTP Basic —— 登录接口是自定义的 JSON 接口
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * 密码编码器。BCrypt 自带随机盐且计算代价可调，
     * 是存密码的默认选择 —— **绝不要用 MD5/SHA 这类快哈希**。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 暴露 AuthenticationManager 供 AuthController 做 JSON 登录用。
     *
     * <p>Boot 4 里没有直接暴露这个 Bean，需要从 {@link AuthenticationConfiguration} 取。
     *
     * <p><b>为什么要加 {@code @ConditionalOnWebApplication}</b>：
     * {@code AuthenticationConfiguration} 是随 Web 安全自动配置一起提供的，
     * 在非 Web 环境（{@code web-application-type=none}）里根本不存在这个 Bean，
     * 于是这条 {@code @Bean} 方法会因为无法满足构造参数而让整个应用启动失败。
     * 而纯终端模式本身不需要认证 —— 它跑在本机上，本来就不要求登录。
     */
    @Bean
    @ConditionalOnWebApplication
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    private static void writeJson(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"%s\"}".formatted(message));
    }
}
