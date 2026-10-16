package com.agent.core;

import com.agent.TestFixtures;
import com.agent.config.AgentProperties;
import com.agent.llm.FakeLlmClient;
import com.agent.llm.LlmException;
import com.agent.llm.model.ChatMessage;
import com.agent.llm.model.ChatRequest;
import com.agent.tool.AgentTool;
import com.agent.tool.ToolRegistry;
import com.agent.llm.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 的 ReAct 循环测试。全程不联网 —— 靠 {@link FakeLlmClient} 按脚本喂响应。
 */
class AgentTest {

    @TempDir
    Path workspace;

    private FakeLlmClient llmClient;
    private StubTool stubTool;

    /** 一个只记录调用、返回固定结果的假工具，用来观察 Agent 怎么驱动工具。 */
    static class StubTool implements AgentTool {
        final AtomicInteger callCount = new AtomicInteger();
        volatile String lastArguments;
        volatile Path lastWorkspace;

        @Override
        public ToolDefinition definition() {
            return ToolDefinition.singleStringParam("stubTool", "测试用工具", "path", "路径");
        }

        @Override
        public String doExecute(String argumentsJson, com.agent.tool.ToolContext context) {
            callCount.incrementAndGet();
            this.lastArguments = argumentsJson;
            // 顺便记下上下文里的工作目录，供"目录确实传到了工具"的断言使用
            this.lastWorkspace = context.workspace();
            return "工具执行结果：找到了 3 个文件";
        }
    }

    @BeforeEach
    void setUp() {
        llmClient = new FakeLlmClient();
        stubTool = new StubTool();
    }

    private Agent buildAgent(int maxToolIterations) {
        AgentProperties props = TestFixtures.props(workspace, maxToolIterations);
        ToolRegistry registry = new ToolRegistry(List.of(stubTool));
        return new Agent(llmClient, registry, props);
    }

    private ChatSession newSession(Agent agent) {
        return new ChatSession("test-session", agent.systemPrompt(workspace), workspace);
    }

    // ---------- 基本对话 ----------

    @Test
    void 模型直接回答时返回该回答并写入历史() {
        Agent agent = buildAgent(8);
        ChatSession session = newSession(agent);
        llmClient.thenAnswer("你好，我是本地 agent。");

        String reply = agent.chat(session, "你好");

        assertThat(reply).isEqualTo("你好，我是本地 agent。");
        // system + user + assistant
        assertThat(session.messages()).hasSize(3);
        assertThat(session.messages().get(2).role()).isEqualTo("assistant");
        assertThat(stubTool.callCount).hasValue(0);
    }

    @Test
    void 工具收到的是会话自己的工作目录() {
        Agent agent = buildAgent(8);
        // 故意用一个与会话不同的目录做对比，确保工具拿到的是会话那个而不是全局配置
        Path sessionWorkspace = workspace.resolve("某个具体项目");
        ChatSession session = new ChatSession("s1", agent.systemPrompt(sessionWorkspace),
                sessionWorkspace);

        llmClient.thenCallTool("call_1", "stubTool", "{}").thenAnswer("好了");
        agent.chat(session, "看看");

        // 这是本次改动的核心：工作目录从"进程级配置"变成了"会话级属性"，
        // 必须真的传到工具手上 —— 否则同一个进程里多个会话会互相看到对方的目录
        assertThat(stubTool.lastWorkspace).isEqualTo(sessionWorkspace);
    }

    @Test
    void 请求里带上了工具定义和配置的模型名() {
        Agent agent = buildAgent(8);
        llmClient.thenAnswer("好的");

        agent.chat(newSession(agent), "你好");

        ChatRequest sent = llmClient.receivedRequests().get(0);
        assertThat(sent.model()).isEqualTo("test-model");
        assertThat(sent.maxTokens()).isEqualTo(512);
        assertThat(sent.stream()).isFalse();
        assertThat(sent.tools()).hasSize(1);
        assertThat(sent.tools().get(0).function().name()).isEqualTo("stubTool");
    }

    // ---------- ReAct 循环 ----------

    @Test
    void 模型请求调用工具时执行工具并把结果回传给模型() {
        Agent agent = buildAgent(8);
        ChatSession session = newSession(agent);

        llmClient.thenCallTool("call_1", "stubTool", "{\"path\":\".\"}")
                .thenAnswer("根据工具结果，目录里有 3 个文件。");

        String reply = agent.chat(session, "有哪些文件？");

        assertThat(reply).isEqualTo("根据工具结果，目录里有 3 个文件。");
        assertThat(stubTool.callCount).hasValue(1);
        assertThat(stubTool.lastArguments).isEqualTo("{\"path\":\".\"}");

        // 第二次请求里必须包含 assistant(tool_calls) 和 tool(结果) 这两条消息，
        // 否则模型不知道自己刚才要干什么，会陷入重复调用
        ChatRequest second = llmClient.receivedRequests().get(1);
        List<ChatMessage> messages = second.messages();
        ChatMessage assistantMsg = messages.get(messages.size() - 2);
        ChatMessage toolMsg = messages.get(messages.size() - 1);

        assertThat(assistantMsg.role()).isEqualTo("assistant");
        assertThat(assistantMsg.toolCalls()).hasSize(1);
        assertThat(toolMsg.role()).isEqualTo("tool");
        assertThat(toolMsg.toolCallId()).isEqualTo("call_1");
        assertThat(toolMsg.content()).contains("找到了 3 个文件");
    }

    @Test
    void 模型连续多轮调用工具时会依次执行() {
        Agent agent = buildAgent(8);
        llmClient.thenCallTool("call_1", "stubTool", "{\"path\":\"a\"}")
                .thenCallTool("call_2", "stubTool", "{\"path\":\"b\"}")
                .thenAnswer("两次都查完了。");

        String reply = agent.chat(newSession(agent), "查两个地方");

        assertThat(reply).isEqualTo("两次都查完了。");
        assertThat(stubTool.callCount).hasValue(2);
        assertThat(llmClient.callCount()).isEqualTo(3);
    }

    @Test
    void 工具不存在时把错误作为结果回传而不是抛异常() {
        Agent agent = buildAgent(8);
        llmClient.thenCallTool("call_1", "不存在的工具", "{}")
                .thenAnswer("那个工具用不了，我直接回答。");

        String reply = agent.chat(newSession(agent), "试试");

        assertThat(reply).isEqualTo("那个工具用不了，我直接回答。");
        ChatRequest second = llmClient.receivedRequests().get(1);
        assertThat(FakeLlmClient.lastMessage(second).content()).contains("不存在名为");
    }

    @Test
    void 超过最大轮数时停止循环并给出提示() {
        Agent agent = buildAgent(2);   // 只允许 2 轮
        // 脚本给 3 步，但第 3 步永远不该被消费到
        llmClient.thenCallTool("c1", "stubTool", "{}")
                .thenCallTool("c2", "stubTool", "{}")
                .thenAnswer("不该走到这里");

        String reply = agent.chat(newSession(agent), "一直查");

        assertThat(reply).contains("已达到工具调用轮数上限 2 轮");
        assertThat(stubTool.callCount).hasValue(2);
        assertThat(llmClient.callCount()).isEqualTo(2);
    }

    // ---------- 推理模型的特殊情况 ----------

    @Test
    void 回答被截断时给出可操作的提示而不是返回空字符串() {
        Agent agent = buildAgent(8);
        llmClient.thenTruncated();

        String reply = agent.chat(newSession(agent), "解释一下这个项目");

        assertThat(reply).contains("回答被截断").contains("max-tokens");
    }

    @Test
    void 思维链不会被写入对话历史() {
        Agent agent = buildAgent(8);
        ChatSession session = newSession(agent);
        llmClient.thenAnswer("最终回答");

        agent.chat(session, "问题");

        // FakeLlmClient 在 reasoning_content 里塞了"这是思维链，不该出现在最终回答里"，
        // 校验它没有混进任何一条历史消息
        assertThat(session.messages())
                .noneMatch(m -> m.content() != null && m.content().contains("思维链"));
    }

    @Test
    void 模型返回空choices时抛出LlmException() {
        Agent agent = buildAgent(8);
        llmClient.thenEmptyChoices();

        assertThatThrownBy(() -> agent.chat(newSession(agent), "你好"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("choices");
    }

    // ---------- 会话 ----------

    @Test
    void 多轮对话会累积历史() {
        Agent agent = buildAgent(8);
        ChatSession session = newSession(agent);

        llmClient.thenAnswer("第一轮回答").thenAnswer("第二轮回答");
        agent.chat(session, "第一个问题");
        agent.chat(session, "第二个问题");

        // system + user + assistant + user + assistant
        assertThat(session.messages()).hasSize(5);

        // 第二轮请求里应该能看到第一轮的问答
        ChatRequest second = llmClient.receivedRequests().get(1);
        assertThat(second.messages()).extracting(ChatMessage::content)
                .contains("第一个问题", "第一轮回答", "第二个问题");
    }

    @Test
    void 清空历史后下一轮不再携带之前的对话() {
        Agent agent = buildAgent(8);
        ChatSession session = newSession(agent);

        llmClient.thenAnswer("第一轮回答");
        agent.chat(session, "第一个问题");
        assertThat(session.messages()).hasSize(3);   // system + user + assistant

        session.clear();

        llmClient.thenAnswer("清空后的回答");
        agent.chat(session, "清空后的问题");

        // 清空后发出的请求里，历史不该再有之前的内容
        ChatRequest afterClear = llmClient.receivedRequests().get(1);
        assertThat(afterClear.messages()).extracting(ChatMessage::content)
                .doesNotContain("第一个问题", "第一轮回答")
                .contains("清空后的问题");
        // system + user
        assertThat(afterClear.messages()).hasSize(2);
        assertThat(afterClear.messages().get(0).role()).isEqualTo("system");
    }

    @Test
    void 系统提示里包含工作目录和可用工具() {
        Agent agent = buildAgent(8);

        String prompt = agent.systemPrompt(workspace);

        assertThat(prompt).contains(workspace.toString()).contains("stubTool");
    }

    @Test
    void 没有工具时也能正常工作() {
        AgentProperties props = TestFixtures.props(workspace);
        Agent agent = new Agent(llmClient, new ToolRegistry(List.of()), props);
        llmClient.thenAnswer("我没有工具也能回答。");

        String reply = agent.chat(newSession(agent), "你好");

        assertThat(reply).isEqualTo("我没有工具也能回答。");
        assertThat(llmClient.receivedRequests().get(0).tools()).isNull();  // 空工具列表不该发出 tools 字段
        assertThat(agent.systemPrompt(workspace)).contains("（无）");
    }

    @Test
    void 工具定义会带上描述和参数说明() {
        Agent agent = buildAgent(8);
        llmClient.thenAnswer("好");

        agent.chat(newSession(agent), "你好");

        ToolDefinition def = llmClient.receivedRequests().get(0).tools().get(0);
        assertThat(def.type()).isEqualTo("function");
        assertThat(def.function().description()).isEqualTo("测试用工具");
        assertThat(def.function().parameters().properties()).containsKey("path");
        assertThat(def.function().parameters().required()).containsExactly("path");
        assertThat(def.function().parameters().properties().get("path").type()).isEqualTo("string");
    }
}
