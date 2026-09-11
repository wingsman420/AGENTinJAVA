package com.agent.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 模型的一次响应。
 *
 * <p>只声明我们真正用得到的字段；{@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * 保证服务端多加字段（比如 {@code system_fingerprint}）时反序列化不会炸 ——
 * 第三方 API 随时可能加字段，这是必须的防御。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(String id, String model, List<Choice> choices, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Integer index, ResponseMessage message,
                         @JsonProperty("finish_reason") String finishReason) {
    }

    /**
     * 响应里的消息。比请求侧的 {@link ChatMessage} 多一个
     * {@code reasoning_content}（思维链），普通消息模型不返回这个字段。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseMessage(
            String role,
            String content,
            @JsonProperty("reasoning_content") String reasoningContent,
            @JsonProperty("tool_calls") List<ToolCall> toolCalls
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                        @JsonProperty("completion_tokens") Integer completionTokens,
                        @JsonProperty("total_tokens") Integer totalTokens) {
    }

    /** 取第一条候选回复；响应结构异常时返回 null 而不是抛异常，交由调用方判断。 */
    public ResponseMessage firstMessage() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).message();
    }

    public String firstFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).finishReason();
    }

    /**
     * 是否因为 token 预算耗尽而截断。
     *
     * <p>推理模型上这个判断很实用：被截断时 {@code content} 可能是空字符串，
     * 直接展示给用户会显得莫名其妙，应该提示"回答被截断，请调大 max-tokens"。
     */
    public boolean isTruncated() {
        return "length".equals(firstFinishReason());
    }
}
