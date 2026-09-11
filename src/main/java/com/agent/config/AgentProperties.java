package com.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

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
        cli = (cli == null) ? new Cli(true) : cli;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 没配 key 时给出明确提示，而不是等到调用 API 才报一个含糊的 401。 */
    public boolean hasApiKey() {
        return !blank(apiKey);
    }
}
