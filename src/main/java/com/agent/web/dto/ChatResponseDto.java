package com.agent.web.dto;

/**
 * 发消息的响应体。
 *
 * <pre>
 * { "conversationId": 1, "reply": "当前目录下有……", "historySize": 5 }
 * </pre>
 *
 * @param conversationId 本次对话所属的会话 id。会话是持久化实体，客户端创建一次、
 *                       之后每次发消息都带上它
 * @param reply          模型的回答
 * @param historySize    该会话当前的消息条数（含 system 与工具消息），可用来观察上下文增长
 */
public record ChatResponseDto(Long conversationId, String reply, int historySize) {
}
