package com.agent.security;

import com.agent.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 把数据库里的用户喂给 Spring Security。
 *
 * <p>登录时框架会拿用户输入的用户名调这里，拿到 {@link UserDetails}（含 BCrypt 哈希），
 * 再用 {@code PasswordEncoder.matches()} 比对密码。本类不碰密码比对 ——
 * 那是框架的职责，自己写比对逻辑极易出错。
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return users.findByUsername(username)
                .map(AppUserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
    }
}
