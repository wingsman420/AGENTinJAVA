package com.agent.web;

import com.agent.security.AppUserPrincipal;
import com.agent.user.User;
import com.agent.user.UserService;
import com.agent.web.dto.AuthDtos.LoginRequest;
import com.agent.web.dto.AuthDtos.RegisterRequest;
import com.agent.web.dto.AuthDtos.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 鉴权的 JSON 接口。
 *
 * <p>没用 Spring Security 自带的表单登录 —— 那会返回一个 HTML 登录页，
 * 而本项目的客户端是 curl 和 API 调用方，需要 JSON。
 * 所以登录流程改成手动：自己调 {@code AuthenticationManager} 认证，
 * 成功后把 SecurityContext 写回 HttpSession。
 */
@RestController
// 只在 Web 模式注册：它依赖 AuthenticationManager，而那个 Bean 由 Web 安全自动配置
// 提供。纯终端模式（web-application-type=none）下没有认证这回事 ——
// 终端跑在本机上，本来就不要求登录。
@ConditionalOnWebApplication
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final AuthenticationManager authenticationManager;

    /**
     * 把认证结果存进 HttpSession。
     *
     * <p>必须显式调用 {@code saveContext} —— 在自定义的登录接口里，
     * 框架不会自动帮你把 SecurityContext 写进会话，只 set 到
     * {@code SecurityContextHolder}（那是线程局部的，请求结束就没了），
     * 结果就是"登录返回成功，但下一个请求依然是未登录"。
     */
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(UserService userService, AuthenticationManager authenticationManager) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
    }

    // 路径写成完整的而不是靠类级 @RequestMapping 拼接 ——
    // /api/me 不在 /api/auth 前缀下，混用会很容易把某个端点挂错位置
    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest request) {
        User user = userService.register(
                request.username(), request.password(), request.displayName());
        log.info("新用户注册: {}", user.getUsername());
        return UserView.from(user);
    }

    @PostMapping("/api/auth/login")
    public UserView login(@Valid @RequestBody LoginRequest request,
                          HttpServletRequest httpRequest,
                          HttpServletResponse httpResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            // 统一成同一句话，**不区分"用户不存在"和"密码错误"** ——
            // 区分开来等于给攻击者一个枚举用户名的接口
            log.info("登录失败: {}", request.username());
            throw new BadCredentialsException("用户名或密码错误");
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        log.info("用户登录: {}", principal.getUsername());
        return new UserView(principal.id(), principal.getUsername(), null, null);
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Map.of("message", "已注销");
    }

    /** 当前登录用户。未登录时被 SecurityConfig 的 entry point 拦成 401。 */
    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal AppUserPrincipal principal) {
        User user = userService.requireById(principal.id());
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "createdAt", user.getCreatedAt().toString());
    }
}
