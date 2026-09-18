package com.agent.conversation;

import com.agent.llm.model.ToolCall;

import java.util.List;

/**
 * 返回给客户端的消息视图。
 *
 * <p>不直接暴露实体：实体带 {@code conversation} 这样的懒加载关联，序列化时会触发
 * 额外的数据库查询（甚至在事务外抛懒加载异常）。用一个扁平的 record 明确划出
 * 对外契约，也避免了"改实体不小心改了 API 响应结构"。
 *
 * <p>{@code hasToolCalls} 只给一个布尔值而不是把 {@code toolCalls} 整个暴露出去 ——
 * 客户端通常只关心"这条是不是工具调用"，完整参数对展示没有价值，反而让响应变大。
 */
public record MessageView(
        int seq,
        String role,
        String content,
        String toolCallId,
        boolean hasToolCalls
) {

    public static MessageView from(Message message) {
        List<ToolCall> calls = message.getToolCalls();
        return new MessageView(
                message.getSeq(),
                message.getRole(),
                message.getContent(),
                message.getToolCallId(),
                calls != null && !calls.isEmpty());
    }
}
