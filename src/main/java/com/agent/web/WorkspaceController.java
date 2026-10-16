package com.agent.web;

import com.agent.conversation.ConversationService;
import com.agent.conversation.WorkspaceView;
import com.agent.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作目录相关接口。
 *
 * <p>单独一个控制器而不是塞进 {@code ConversationController}：后者的类级映射是
 * {@code /api/conversations}，而 {@code /api/workspaces} 不在那个前缀下 ——
 * 硬塞进去要么改动类级映射（影响所有端点），要么拼出 {@code /../workspaces}
 * 这种无意义的路径。
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final ConversationService conversations;

    public WorkspaceController(ConversationService conversations) {
        this.conversations = conversations;
    }

    /**
     * 列出当前用户用过的工作目录，最近活跃的在前。
     *
     * <p>返回的列表是从会话派生出来的（不是单独的表），所以每一项都保证
     * 至少有一个会话可以打开。
     */
    @GetMapping
    public List<WorkspaceView> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return conversations.listWorkspaces(principal.id());
    }
}
