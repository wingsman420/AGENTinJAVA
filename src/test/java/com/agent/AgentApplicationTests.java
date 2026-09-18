package com.agent;

import com.agent.core.Agent;
import com.agent.llm.LlmClient;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟测试：整个 Spring 容器能不能正常启动。
 *
 * <p>这个测试的价值在于抓**装配层面的错误** —— 配置属性绑定不上、
 * Bean 循环依赖、工具没被扫描到、{@code @ConditionalOnProperty} 写错，
 * 这些都不会被单元测试发现，只有真启动一次容器才暴露。
 *
 * <p>两个关键设置：
 * <ul>
 *   <li>{@code agent.cli.enabled=false} —— 否则 {@code TerminalChatRunner}
 *       会试图读 stdin，在测试环境里可能挂住不返回</li>
 *   <li>{@code agent.api-key=test-key} —— {@code AgentConfig} 在缺 key 时会
 *       快速失败，这里给个占位值。**注意不会真的发请求**，容器启动本身不调 API</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "agent.cli.enabled=false",
        "agent.api-key=test-key"
})
// 显式激活测试配置（src/test/resources/application-test.yml）。
//
// 不加这行测试其实也能跑 —— 因为 H2 在 test classpath 上，Spring Boot 会自动
// 配一个嵌入式内存库。但那是**隐式行为**：哪天有人在 application.yml 里加了
// spring.datasource.url，自动配置就不再兜底，测试会转去连真实的 MySQL。
// 显式声明数据源来源，测试才不会随主配置的改动而意外失效。
@ActiveProfiles("test")
class AgentApplicationTests {

    @Autowired
    private Agent agent;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private LlmClient llmClient;

    @Test
    void 容器能正常启动() {
        assertThat(llmClient).isNotNull();
        assertThat(agent).isNotNull();
    }

    @Test
    void 三个文件工具都被自动注册() {
        assertThat(toolRegistry.toolNames())
                .containsExactlyInAnyOrder("listFiles", "readFile", "searchCode");
    }

    @Test
    void 系统提示里带上了工作目录() {
        assertThat(agent.systemPrompt()).contains("当前工作目录");
    }
}
