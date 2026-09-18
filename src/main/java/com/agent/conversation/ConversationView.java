package com.agent.conversation;

import java.time.Instant;
import java.util.List;

/**
 * 返回给客户端的会话视图。
 *
 * <p>列表接口用 {@link #summary}（不含消息，只给条数），详情接口用 {@link #detail}
 * （含完整消息历史）。如果列表接口也返回全部消息，会话一多响应就会非常臃肿 ——
 * 而列表页本来也不需要历史内容。
 */
public record ConversationView(
        Long id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        long messageCount,
        List<MessageView> messages
) {

    /** 列表用：不含消息内容，{@code messages} 为 null。 */
    public static ConversationView summary(Conversation conversation, long messageCount) {
        return new ConversationView(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                messageCount,
                null);
    }

    /** 详情用：含完整消息历史。 */
    public static ConversationView detail(Conversation conversation, List<MessageView> messages) {
        return new ConversationView(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt(),
                messages.size(),
                messages);
    }
}
