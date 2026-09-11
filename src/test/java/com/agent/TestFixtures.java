package com.agent;

import com.agent.config.AgentProperties;

import java.nio.file.Path;
import java.time.Duration;

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
                new AgentProperties.Cli(false));
    }
}
