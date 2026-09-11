package com.agent.llm;

import com.agent.llm.model.ChatRequest;
import com.agent.llm.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * DeepSeek 客户端。DeepSeek 用的是 OpenAI 兼容协议，所以这个类
 * 稍作修改（换 baseUrl 和 model）就能对接通义、智谱、月之暗面、本地 Ollama 等。
 *
 * <h2>为什么手动用 JsonMapper 序列化，而不是让 RestClient 直接收 record</h2>
 * 有两条路：让 RestClient 的消息转换器直接处理 record，或者自己转成 String 再发。
 * 这里选后者，原因是**可调试性**：大模型接口出问题时（字段名不认、模型不按格式返回），
 * 最常见的排查手段就是把原始请求体和原始响应体完整打出来看。自己控制序列化，
 * 就能在 DEBUG 日志里留下未经任何转换的原文；交给转换器则很难拿到这一层。
 * 另外这也绕开了"手动构造的 RestClient 到底注册了哪个 Jackson 转换器"的不确定性
 * —— Spring Boot 4 换成了 Jackson 3，转换器注册情况并不直观。
 */
public class DeepSeekClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    /** 与 OpenAI 协议一致的对话补全路径，拼在 baseUrl 之后。 */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public DeepSeekClient(RestClient restClient, JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String payload = jsonMapper.writeValueAsString(request);
        if (log.isDebugEnabled()) {
            log.debug("→ 发送给模型的请求: {}", payload);
        }

        String raw;
        try {
            raw = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .requiredBody(String.class);
        } catch (RestClientException e) {
            throw new LlmException("调用模型接口失败: " + e.getMessage(), e);
        }

        if (log.isDebugEnabled()) {
            log.debug("← 模型的响应: {}", raw);
        }

        try {
            return jsonMapper.readValue(raw, ChatResponse.class);
        } catch (RuntimeException e) {
            // Jackson 3 的异常是非受检的，这里统一成 LlmException 便于上层处理
            throw new LlmException("模型响应无法解析成 JSON: " + raw, e);
        }
    }
}
