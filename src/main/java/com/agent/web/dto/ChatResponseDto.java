package com.agent.web.dto;

/**
 * Web 对话接口的响应体。
 *
 * <pre>
 * { "sessionId": "abc", "reply": "当前目录下有……", "historySize": 5 }
 * </pre>
 *
 * @param sessionId   本次会话标识。**首次请求时客户端必须把它存下来**，
 *                    下次带上才能延续上下文
 * @param reply       模型的回答
 * @param historySize 该会话当前的消息条数（含 system 与工具消息），可用来观察上下文增长
 */
public record ChatResponseDto(String sessionId, String reply, int historySize) {
}
