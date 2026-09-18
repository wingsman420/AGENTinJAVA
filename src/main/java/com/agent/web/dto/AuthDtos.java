package com.agent.web.dto;

import com.agent.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 鉴权相关的请求/响应体。
 *
 * <p>集中放在一个文件里是因为它们都很小、且只服务于同一组接口 ——
 * 拆成三个文件反而要在包列表里来回找。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(max = 50, message = "用户名最长 50 个字符")
            String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 100, message = "密码长度需在 6~100 之间")
            String password,

            String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }

    /** 对外的用户视图。**绝不包含 passwordHash**。 */
    public record UserView(Long id, String username, String displayName, Instant createdAt) {
        public static UserView from(User user) {
            return new UserView(user.getId(), user.getUsername(),
                    user.getDisplayName(), user.getCreatedAt());
        }
    }
}
