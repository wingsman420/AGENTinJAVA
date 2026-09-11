package com.agent.config;

import com.agent.llm.DeepSeekClient;
import com.agent.llm.LlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * 把配置和 LLM 客户端装配成 Spring Bean。
 *
 * <p>这里刻意**不用** Spring Boot 自动配置的 {@code RestClient.Builder}：
 * Spring Boot 4 把自动配置拆成了多个模块，{@code RestClientAutoConfiguration}
 * 不在当前依赖图里（它在独立的 {@code spring-boot-starter-restclient} 模块），
 * 所以 {@code @Autowired RestClient.Builder} 会导致启动失败。
 * 直接 {@code RestClient.builder()} 自己建，行为完全可控。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

    @Bean
    public RestClient agentRestClient(AgentProperties props) {
        // 大模型接口偶尔会慢，尤其推理模型；超时给足，否则长回答会被拦腰掐断
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(props.timeout());

        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .build();
    }

    @Bean
    public LlmClient llmClient(RestClient agentRestClient, JsonMapper jsonMapper, AgentProperties props) {
        if (!props.hasApiKey()) {
            // 快速失败：与其等到用户提问时才报一个含糊的 401，
            // 不如启动时就明确告诉他 key 没配、以及怎么配
            throw new IllegalStateException("""
                    未配置 DeepSeek API Key。请任选一种方式配置：
                      1) 环境变量  DEEPSEEK_API_KEY=sk-xxxx
                      2) 启动参数  java -jar target/agent-cli-0.1.0.jar --agent.api-key=sk-xxxx
                      3) 本地配置文件 src/main/resources/application-local.yml（已在 .gitignore 中）:
                           agent:
                             api-key: sk-xxxx
                         并以 --spring.profiles.active=local 启动
                    """);
        }
        return new DeepSeekClient(agentRestClient, jsonMapper);
    }
}
