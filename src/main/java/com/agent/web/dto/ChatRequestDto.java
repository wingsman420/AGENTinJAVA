package com.agent.web.dto;

/**
 * Web 对话接口的请求体。
 *
 * <pre>
 * POST /api/chat
 * { "sessionId": "abc", "message": "当前目录有哪些文件？" }
 * </pre>
 *
 * <p>{@code sessionId} 可以省略，服务端会自动生成一个并在响应里返回。
 * 后续请求带上同一个 id，就能接着之前的上下文继续聊。
 */
public record ChatRequestDto(String sessionId, String message) {
}
