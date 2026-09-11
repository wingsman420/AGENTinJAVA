package com.agent.llm;

/**
 * 调用大模型失败时抛出。
 *
 * <p>把底层五花八门的异常（连接超时、401 鉴权失败、429 限流、JSON 解析失败……）
 * 统一成一种，让上层 {@code Agent} 只需要处理一种情况。同时保留 cause，
 * 排查时仍然能看到根因。
 *
 * <p>是**非受检异常**：调用方在绝大多数场景下无能为力（总不能让用户重输吧），
 * 强制 try/catch 只会污染代码。需要处理的边界（比如 CLI 想打印友好提示）
 * 可以按需捕获。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
