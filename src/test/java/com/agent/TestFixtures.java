package com.agent;

import com.agent.config.AgentProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 测试用的公共构造辅助。 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** 构造一份指向指定工作目录的配置；其余字段用测试友好的小值。 */
    public static AgentProperties props(Path workspace) {
        return props(workspace, 8);
    }

    public static AgentProperties props(Path workspace, int maxToolIterations) {
        return new AgentProperties(
                "http://localhost:1",   // 测试不会真的发请求，随便填
                "test-key",
                "test-model",
                512,
                Duration.ofSeconds(5),
                maxToolIterations,
                workspace,
                List.of(),              // 默认不配白名单（= 不限制），与服务默认行为一致
                new AgentProperties.Cli(false));
    }

    /** 配了工作目录白名单的配置，用来测越界拒绝。 */
    public static AgentProperties propsWithAllowedRoots(Path workspace, List<Path> allowedRoots) {
        return new AgentProperties(
                "http://localhost:1", "test-key", "test-model",
                512, Duration.ofSeconds(5), 8,
                workspace, allowedRoots, new AgentProperties.Cli(false));
    }
}
