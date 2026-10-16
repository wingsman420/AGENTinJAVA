package com.agent.conversation;

import java.time.Instant;

/**
 * 一个"用过的工作目录"。
 *
 * <p>不是实体 —— 没有对应的表。它是从 {@code conversations} 表里
 * {@code GROUP BY workspace} 派生出来的视图：
 *
 * <pre>
 * SELECT new WorkspaceView(c.workspace, COUNT(c), MAX(c.updatedAt))
 * FROM Conversation c WHERE c.user.id = :userId
 * GROUP BY c.workspace ORDER BY MAX(c.updatedAt) DESC
 * </pre>
 *
 * <p>用派生而不是单独建表，是为了让"目录列表"天然与实际有对话的地方一致 ——
 * 单独建表会多一份需要同步的状态，删会话时忘了清理就会出现"列表里有、点进去空的"。
 *
 * @param path              工作目录的绝对路径
 * @param conversationCount 该目录下的会话数（用于在菜单里显示"3 个对话"）
 * @param lastUsedAt        最近一次使用时间（用于排序）
 */
public record WorkspaceView(String path, Long conversationCount, Instant lastUsedAt) {
}
