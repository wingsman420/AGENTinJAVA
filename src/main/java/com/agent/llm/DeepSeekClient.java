package com.agent.llm;

import com.agent.llm.model.ChatRequest;
import com.agent.llm.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
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
        } catch (RestClientResponseException e) {
            // 服务端返回了非 2xx 状态码
            throw new LlmException(describeHttpError(e), e);
        } catch (RestClientException e) {
            // 连不上、超时之类的传输层问题，压根没拿到状态码
            throw new LlmException("调用模型接口失败（网络层）：" + e.getMessage(), e);
        }

        if (log.isDebugEnabled()) {
            log.debug("← 模型的响应: {}", raw);
        }

        try {
            return jsonMapper.readValue(raw, ChatResponse.class);
        } catch (RuntimeException e) {
            // Jackson 3 的异常是非受检的，这里统一成 LlmException 便于上层处理
            throw new LlmException("模型响应无法解析成 JSON: " + truncate(raw, 500), e);
        }
    }

    /**
     * 把 HTTP 状态码翻译成**可诊断**的错误信息。
     *
     * <p>为什么不能只抛一句"请求失败"：调用方拿到错误后要决定"能不能重试"，
     * 而不同状态码的答案完全不同 —— 401 重试一百次也没用（Key 就是错的），
     * 429 和 5xx 稍等重试往往就好了。把状态码和响应体一起带上，
     * 排查时才能一眼看出问题出在哪一层。
     *
     * <p>尤其要避免的情况是：不检查状态码就直接去解析响应体，
     * 那样所有错误都会变成一句含糊的"响应里缺少 choices"，
     * 掩盖掉真正的 401 / 429。
     */
    private static String describeHttpError(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String hint = switch (status) {
            case 400 -> "请求格式不合协议（消息结构或参数有问题），重试通常不会好转";
            case 401 -> "鉴权失败：API Key 无效、缺失或格式错误，请检查 agent.api-key 配置";
            case 403 -> "无权访问该模型，请确认账号已开通";
            case 404 -> "接口路径不存在，请检查 agent.base-url 是否正确";
            case 429 -> "请求被限流：额度不足或请求过于频繁，稍后重试";
            default -> (status >= 500)
                    ? "模型服务端异常，属对方临时故障，可以重试"
                    : "请求被拒绝";
        };
        return "调用模型接口失败（HTTP %d）：%s。响应体：%s"
                .formatted(status, hint, truncate(e.getResponseBodyAsString(), 500));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "(空)";
        }
        return (text.length() <= maxLength) ? text : text.substring(0, maxLength) + "...";
    }
}
