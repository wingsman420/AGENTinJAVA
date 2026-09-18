package com.agent.web.dto;

import jakarta.validation.constraints.Size;

/** 会话相关的请求体。 */
public final class ConversationDtos {

    private ConversationDtos() {
    }

    /**
     * 新建会话。标题可省略 —— 省略时用"新会话"，
     * 或者在第一次对话后由业务侧按首条用户消息补上。
     */
    public record CreateConversationRequest(
            @Size(max = 200, message = "标题最长 200 个字符") String title
    ) {
    }

    public record RenameRequest(
            @Size(max = 200, message = "标题最长 200 个字符") String title
    ) {
    }
}
