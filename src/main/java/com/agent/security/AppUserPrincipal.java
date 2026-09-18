package com.agent.security;

import com.agent.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 放在 SecurityContext 里的"当前登录用户"。
 *
 * <p><b>为什么要自定义而不是直接用 Spring 的 {@code User}</b>：
 * 业务层需要的是**用户 id**（会话表存的是 user_id），而 {@code UserDetails}
 * 只暴露用户名。每处理一个请求都拿用户名回数据库查一次 id 是没必要的往返 ——
 * 把 id 直接带在 principal 上，一次查询就够了。
 *
 * <p>用 record 实现是可行的：{@code UserDetails} 的
 * {@code isAccountNonExpired}/{@code isAccountNonLocked} 等四个方法都有默认实现，
 * 所以只需要提供 authorities、password、username 三样。
 */
public record AppUserPrincipal(Long id, String username, String passwordHash)
        implements UserDetails {

    public static AppUserPrincipal from(User user) {
        return new AppUserPrincipal(user.getId(), user.getUsername(), user.getPasswordHash());
    }

    /** 本项目的权限模型很简单：登录了就是普通用户，没有角色分级。 */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
