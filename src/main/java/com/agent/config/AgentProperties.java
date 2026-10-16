package com.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * agent 的全部可配置项，绑定 application.yml 里 {@code agent.*} 前缀的配置。
 *
 * <p>用 record 写配置类时，Spring Boot 会**隐式使用构造函数绑定**，
 * 不需要加 {@code @ConstructorBinding}（那是老版本的写法）。
 * 前提是 record 只有一个全参构造器 —— 这正是 record 的天然形态。
 *
 * <p>紧凑构造器（compact constructor）里给所有字段兜底默认值，
 * 这样 application.yml 少配了某一项也不会出现 null 或 0。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        String baseUrl,
        String apiKey,
        String model,
        Integer maxTokens,
        Duration timeout,
        Integer maxToolIterations,
        Path workspace,

        /**
         * 允许作为工作目录的根路径白名单。
         *
         * <p><b>为什么需要这个</b>：工作目录现在可以由客户端指定（建会话时传 workspace），
         * 而它决定路径沙箱的根。也就是说，登录用户能把沙箱根设成任意路径，
         * 进而用文件工具读那台机器上任何目录 —— 这是引入会话级工作目录后
         * **新出现的攻击面**，以前沙箱根是服务端配置、客户端改不了。
         *
         * <p>列表非空时，工作目录必须落在其中之一（含子目录）以内。
         * 留空表示不限制 —— 本地自用方便，但对外部署必须配。
         * 留空时启动会打一条 WARN 提醒。
         */
        List<Path> allowedRoots,

        Cli cli
) {

    /** 终端对话入口的开关。服务器部署时用 {@code --agent.cli.enabled=false} 关掉。 */
    public record Cli(boolean enabled) {
    }

    public AgentProperties {
        baseUrl = blank(baseUrl) ? "https://api.deepseek.com" : baseUrl;
        model = blank(model) ? "deepseek-flash" : model;

        // 推理模型（deepseek-v4-pro）会先输出思维链再给答案，
        // maxTokens 给小了会被思维链吃光，导致 content 返回空字符串。
        maxTokens = (maxTokens == null || maxTokens <= 0) ? 2048 : maxTokens;

        timeout = (timeout == null) ? Duration.ofSeconds(120) : timeout;
        maxToolIterations = (maxToolIterations == null || maxToolIterations <= 0) ? 8 : maxToolIterations;
        workspace = (workspace == null) ? Path.of("").toAbsolutePath() : workspace.toAbsolutePath();

        // 白名单路径统一规范化，否则 "D:\proj" 和 "D:\proj\" 这种写法差异会让比对失败
        allowedRoots = (allowedRoots == null) ? List.of() : allowedRoots.stream()
                .filter(Objects::nonNull)
                .map(p -> p.toAbsolutePath().normalize())
                .toList();

        cli = (cli == null) ? new Cli(true) : cli;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 没配 key 时给出明确提示，而不是等到调用 API 才报一个含糊的 401。 */
    public boolean hasApiKey() {
        return !blank(apiKey);
    }

    /** 是否配置了工作目录白名单。没配表示不限制。 */
    public boolean hasAllowedRoots() {
        return !allowedRoots.isEmpty();
    }

    /**
     * 判断某个工作目录是否被允许。
     *
     * <p>没配白名单时一律放行（本地自用的默认行为）。
     */
    public boolean isWorkspaceAllowed(Path candidate) {
        if (allowedRoots.isEmpty()) {
            return true;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        return allowedRoots.stream().anyMatch(normalized::startsWith);
    }
}
