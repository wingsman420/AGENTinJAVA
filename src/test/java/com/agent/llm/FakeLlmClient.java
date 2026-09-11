package com.agent.llm;

import com.agent.llm.model.ChatMessage;
import com.agent.llm.model.ChatRequest;
import com.agent.llm.model.ChatResponse;
import com.agent.llm.model.ToolCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 测试用的假客户端，按预设脚本依次返回响应。
 *
 * <p>这是整个测试策略的支点。有了它，{@code Agent} 的 ReAct 循环逻辑可以：
 * <ul>
 *   <li>**不联网** —— 测试离线可跑，CI 里不会因为网络抖动变红</li>
 *   <li>**不花钱** —— 每次跑测试都真调 API 的话，余额很快就没了</li>
 *   <li>**可构造边界场景** —— "模型连续调三轮工具"、"响应被截断"、
 *       "模型返回空 choices"这些真实 API 很难复现的情况，这里一句话就能造出来</li>
 * </ul>
 *
 * <p>这正是 {@code LlmClient} 抽成接口的价值所在。
 */
public class FakeLlmClient implements LlmClient {

    private final Deque<ChatResponse> scripted = new ArrayDeque<>();
    private final List<ChatRequest> receivedRequests = new ArrayList<>();

    // ---------- 脚本编排 ----------

    /** 追加一步：模型直接给出文本回答（不调工具）。 */
    public FakeLlmClient thenAnswer(String content) {
        return thenRespond(text(content));
    }

    /** 追加一步：模型请求调用某个工具。 */
    public FakeLlmClient thenCallTool(String callId, String toolName, String argumentsJson) {
        return thenRespond(toolCall(callId, toolName, argumentsJson));
    }

    /** 追加一步：模拟回答被截断（finish_reason=length 且 content 为空）。 */
    public FakeLlmClient thenTruncated() {
        return thenRespond(new ChatResponse("fake", "test-model",
                List.of(new ChatResponse.Choice(0,
                        new ChatResponse.ResponseMessage("assistant", "", "思维链把预算用光了", null),
                        "length")),
                null));
    }

    /** 追加一步：模型返回空 choices（异常响应）。 */
    public FakeLlmClient thenEmptyChoices() {
        return thenRespond(new ChatResponse("fake", "test-model", List.of(), null));
    }

    public FakeLlmClient thenRespond(ChatResponse response) {
        scripted.add(response);
        return this;
    }

    // ---------- 断言辅助 ----------

    /** 实际收到的请求，按顺序。用来验证"工具结果有没有原样回传给模型"。 */
    public List<ChatRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    public int callCount() {
        return receivedRequests.size();
    }

    // ---------- LlmClient ----------

    @Override
    public ChatResponse chat(ChatRequest request) {
        receivedRequests.add(request);
        ChatResponse next = scripted.poll();
        if (next == null) {
            throw new AssertionError(
                    "FakeLlmClient 的预设脚本已用尽，但又被调用了第 %d 次。"
                            .formatted(receivedRequests.size())
                            + "通常说明 Agent 的循环次数超出了预期。");
        }
        return next;
    }

    // ---------- 响应构造 ----------

    public static ChatResponse text(String content) {
        return new ChatResponse("fake", "test-model",
                List.of(new ChatResponse.Choice(0,
                        new ChatResponse.ResponseMessage("assistant", content, "这是思维链，不该出现在最终回答里", null),
                        "stop")),
                null);
    }

    public static ChatResponse toolCall(String callId, String toolName, String argumentsJson) {
        ToolCall call = new ToolCall(callId, "function", new ToolCall.Function(toolName, argumentsJson));
        return new ChatResponse("fake", "test-model",
                List.of(new ChatResponse.Choice(0,
                        new ChatResponse.ResponseMessage("assistant", "", "模型在想该调哪个工具", List.of(call)),
                        "tool_calls")),
                null);
    }

    /** 从请求里取最后一条消息，便于断言。 */
    public static ChatMessage lastMessage(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        return messages.get(messages.size() - 1);
    }
}
