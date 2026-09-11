package com.agent.core;

import com.agent.config.AgentProperties;
import com.agent.llm.LlmClient;
import com.agent.llm.LlmException;
import com.agent.llm.model.ChatMessage;
import com.agent.llm.model.ChatRequest;
import com.agent.llm.model.ChatResponse;
import com.agent.llm.model.ToolCall;
import com.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 的核心：ReAct 循环。
 *
 * <p>循环长这样：
 * <pre>
 *   把用户输入追加进历史
 *        ↓
 *   连历史一起发给模型（附带工具清单）
 *        ↓
 *   模型想调工具？ ── 是 ──→ 执行工具 → 结果作为 tool 消息追加 → 回到"发给模型"
 *        │
 *        否
 *        ↓
 *   这就是最终回答，返回
 * </pre>
 *
 * <p>"ReAct" = Reasoning + Acting：模型先推理该做什么，再动手做，
 * 看完结果继续推理 —— 直到它有足够信息回答。这就是它和"一问一答"的本质区别，
 * 也是 agent 能完成"帮我看看项目里 X 在哪里用到了"这类多步任务的原因。
 *
 * <p>两个关键细节：
 * <ul>
 *   <li>**工具结果要原样回传**，并且带上 {@code toolCallId} 指回是哪次调用。
 *       不这样做模型会不知道自己刚才要干什么，进而反复调用同一个工具。</li>
 *   <li>**必须有轮数上限**。模型可能陷入"调工具 → 不满意 → 再调"的死循环，
 *       没有上限就会一直烧 token。</li>
 * </ul>
 */
@Component
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final AgentProperties props;

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry, AgentProperties props) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.props = props;
    }

    /**
     * 系统提示词：设定 agent 的身份和行为边界。
     *
     * <p>这段文字不进对话历史之外的地方 —— 它是每轮请求的第一条消息。
     * 写得具体比写得长更重要：明确的"先 listFiles 再 readFile"这类顺序指引，
     * 能显著减少模型瞎猜文件名的次数。
     */
    public String systemPrompt() {
        return """
                你是一个运行在用户本机上的 Java 项目开发助手，可以通过工具查看用户的本地文件。

                工作规则：
                1. 想了解项目结构时，先用 listFiles 查看目录，不要凭空猜测文件名。
                2. 要看文件内容用 readFile；要定位某个类/方法/变量出现在哪里用 searchCode。
                3. 你的回答要基于工具返回的真实文件内容，不要编造。引用时请说明来自哪个文件。
                4. 工具返回错误时（文件不存在、路径越界等），根据错误信息调整做法，
                   不要重复发起同样的调用。
                5. 你只能访问工作目录以内的文件，越界会被拒绝，这是设计如此，不必反复尝试。
                6. 用中文回答，直接给出结论，不要复述工具返回的原始内容。

                当前工作目录：%s
                可用工具：%s
                """.formatted(
                props.workspace(),
                toolRegistry.isEmpty() ? "（无）" : String.join(", ", toolRegistry.toolNames()));
    }

    /**
     * 处理一轮用户输入，返回模型的最终回答。
     *
     * <p>整个过程对同一个 {@link ChatSession} 加锁，避免并发请求把历史搅乱。
     */
    public String chat(ChatSession session, String userInput) {
        synchronized (session) {
            session.add(ChatMessage.user(userInput));

            for (int round = 1; round <= props.maxToolIterations(); round++) {
                ChatResponse response = llmClient.chat(ChatRequest.withTools(
                        props.model(), session.messages(), props.maxTokens(), toolRegistry.definitions()));

                ChatResponse.ResponseMessage message = response.firstMessage();
                if (message == null) {
                    throw new LlmException("模型没有返回任何候选回复（choices 为空）");
                }

                if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
                    return finish(session, response, message);
                }

                log.debug("第 {} 轮：模型请求调用 {} 个工具", round, message.toolCalls().size());

                // 先原样记下模型这次的决定，再追加工具结果。
                // 顺序不能反：协议要求 tool 消息紧跟在发起它的 assistant 消息之后。
                session.add(ChatMessage.assistant(message.content(), message.toolCalls()));

                for (ToolCall call : message.toolCalls()) {
                    String result = toolRegistry.execute(call.function().name(), call.function().arguments());
                    session.add(ChatMessage.tool(call.id(), result));
                }
            }

            String exhausted = "（已达到工具调用轮数上限 %d 轮，停止继续调用工具。"
                    .formatted(props.maxToolIterations())
                    + "如果任务还没完成，请把问题拆小一些再问，或调大 agent.max-tool-iterations。）";
            session.add(ChatMessage.assistant(exhausted, null));
            log.warn("达到工具调用轮数上限 {}", props.maxToolIterations());
            return exhausted;
        }
    }

    /** 收尾：把模型的最终回答写进历史并返回。 */
    private String finish(ChatSession session, ChatResponse response, ChatResponse.ResponseMessage message) {
        String content = message.content() == null ? "" : message.content();

        if (content.isBlank() && response.isTruncated()) {
            // 推理模型的典型症状：思维链把 token 预算吃光，答案还没开始写就被截断。
            // 直接返回空字符串会让用户一头雾水，所以给一句可操作的提示。
            content = "（回答被截断：token 预算被模型的思维链耗尽，content 为空。"
                    + "请调大 agent.max-tokens 后重试。）";
            log.warn("模型回答被截断，finish_reason=length");
        }

        // 注意：这里刻意不保存 reasoningContent。
        // 思维链是模型的内部推理过程，既不该展示给用户，回传到下一轮也只会白白消耗 token。
        session.add(ChatMessage.assistant(content, null));
        return content;
    }

    /** 供 Web 层展示用量信息。 */
    public AgentProperties properties() {
        return props;
    }

    /** 当前发给模型的工具名，测试和排查用。 */
    public List<String> activeToolNames() {
        return List.copyOf(toolRegistry.toolNames());
    }
}
