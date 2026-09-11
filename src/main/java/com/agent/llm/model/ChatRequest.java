package com.agent.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 发给模型的一次请求。
 *
 * <p>{@code maxTokens} 这里特别说明一下：DeepSeek 的 {@code deepseek-v4-pro} 是
 * **推理模型**，会先输出一段思维链（响应里的 {@code reasoning_content}）再给答案，
 * 而思维链同样消耗 token 预算。实测 {@code maxTokens=20} 时思维链就把预算吃光了，
 * 导致 {@code content} 返回空字符串、{@code finish_reason} 是 {@code "length"}。
 * 所以默认值给到了 2048。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        Boolean stream,
        List<ToolDefinition> tools
) {

    /** 不带工具的普通对话。 */
    public static ChatRequest of(String model, List<ChatMessage> messages, int maxTokens) {
        return new ChatRequest(model, messages, maxTokens, false, null);
    }

    /** 带工具的对话，模型可以选择调用工具。 */
    public static ChatRequest withTools(String model, List<ChatMessage> messages,
                                        int maxTokens, List<ToolDefinition> tools) {
        return new ChatRequest(model, messages, maxTokens, false,
                (tools == null || tools.isEmpty()) ? null : tools);
    }
}
