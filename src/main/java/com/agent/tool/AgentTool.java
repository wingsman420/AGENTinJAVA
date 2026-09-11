package com.agent.tool;

import com.agent.llm.model.ToolDefinition;

/**
 * 一个 agent 可以调用的工具。
 *
 * <p>所有工具统一用 {@code String execute(String argumentsJson)} 这个签名 ——
 * 参数是一段 JSON 文本，返回值是给模型看的文本。这样 {@code ToolRegistry}
 * 就能用同一个循环处理所有工具，将来加新工具不需要改任何既有代码
 * （这就是"面向接口编程"在这个项目里最直接的收益）。
 *
 * <h2>工具不应该抛异常来表示业务失败</h2>
 * 文件不存在、关键字没搜到、路径越界这类情况，应该作为正常结果文本返回给模型，
 * 让它自己决定下一步（换个路径再试、或者告诉用户找不到）。抛异常会让整轮对话中断，
 * agent 也就失去了"自己纠错"的能力。
 *
 * <p>为了不让这条约定沦为"靠自觉"，接口用**模板方法**把它固化成结构：
 * 外部永远调 {@link #execute}，它负责把越界异常转成错误文本；
 * 子类只实现 {@link #doExecute}，专心写自己的逻辑。
 * 将来新增工具时即使忘了处理异常，行为也是对的。
 * （命名借用 Servlet 的 {@code doGet}/{@code doPost} 惯例。）
 */
public interface AgentTool {

    /** 工具定义，会随每次请求发给模型，决定模型知不知道这个工具怎么用。 */
    ToolDefinition definition();

    /** 工具名，取自定义，避免两处写得不一致。 */
    default String name() {
        return definition().function().name();
    }

    /**
     * 执行工具，返回给模型的文本结果。
     *
     * <p>不要重写这个方法 —— 重写 {@link #doExecute} 即可。
     * 这里集中做异常兜底，保证任何工具都不会因为路径越界而中断整轮对话。
     */
    default String execute(String argumentsJson) {
        try {
            return doExecute(argumentsJson);
        } catch (WorkspaceViolationException e) {
            return "错误：" + e.getMessage();
        }
    }

    /** 子类实现真正的工具逻辑。 */
    String doExecute(String argumentsJson);
}
