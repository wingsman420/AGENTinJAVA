package com.agent.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 对话历史里的一条消息。这是发给模型的 {@code messages} 数组的元素。
 *
 * <p>四种角色（role），构成一次完整的工具调用回合：
 * <pre>
 * system      : 系统提示，设定 agent 的身份和行为边界
 * user        : 用户说的话
 * assistant   : 模型说的话；带 toolCalls 时表示"我要调工具"
 * tool        : 工具执行结果，必须带上 toolCallId 指回是哪次调用的结果
 * </pre>
 *
 * <p>{@code @JsonInclude(NON_NULL)} 让值为 null 的字段不出现在 JSON 里 ——
 * 比如普通消息不该带 {@code tool_calls} 字段，否则协议可能不认。
 *
 * <p>字段名用 {@code @JsonProperty} 显式写成下划线风格，因为协议的字段名是
 * {@code tool_calls} / {@code tool_call_id}，而 Java 习惯用驼峰。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    /**
     * 模型的消息。content 允许为空字符串（只调工具不说话的回合里，
     * 协议要求这个字段存在）——所以这里把 null 规整成 ""。
     */
    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content == null ? "" : content, toolCalls, null);
    }

    /** 工具执行结果。toolCallId 必须与模型发来的那次调用的 id 一致。 */
    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
