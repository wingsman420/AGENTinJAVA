package com.agent.web.dto;

import jakarta.validation.constraints.Size;

/** 会话相关的请求体。 */
public final class ConversationDtos {

    private ConversationDtos() {
    }

    /**
     * 新建会话。
     *
     * @param title     标题，可省略（省略时用"新会话"）
     * @param workspace 该会话的工作目录（绝对路径）。**可省略** —— 省略时用服务端配置的
     *                  默认目录。设计成可选而不是必填，是为了兼容既有客户端：
     *                  之前建会话只需要 title，改成必填会让所有旧调用直接 400
     */
    public record CreateConversationRequest(
            @Size(max = 200, message = "标题最长 200 个字符") String title,
            String workspace
    ) {
    }

    public record RenameRequest(
            @Size(max = 200, message = "标题最长 200 个字符") String title
    ) {
    }
}
