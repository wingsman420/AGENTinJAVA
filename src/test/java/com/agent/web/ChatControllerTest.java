package com.agent.web;

import com.agent.TestFixtures;
import com.agent.config.AgentProperties;
import com.agent.core.Agent;
import com.agent.core.SessionStore;
import com.agent.llm.FakeLlmClient;
import com.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层测试。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @SpringBootTest}：只挂载这一个
 * 控制器，不启动整个 Spring 容器，跑起来快得多。而且这里用的是**真实的**
 * {@code Agent}、{@code SessionStore}，只把最外层的 {@code LlmClient}
 * 换成假的 —— 于是 HTTP 层到业务逻辑这一段是真实贯通的。
 */
class ChatControllerTest {

    @TempDir
    Path workspace;

    private MockMvc mockMvc;
    private FakeLlmClient llmClient;
    private SessionStore sessions;

    @BeforeEach
    void setUp() {
        llmClient = new FakeLlmClient();
        AgentProperties props = TestFixtures.props(workspace);
        Agent agent = new Agent(llmClient, new ToolRegistry(List.of()), props);
        sessions = new SessionStore(agent);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(agent, sessions, props)).build();
    }

    private MvcResult postChat(String json) throws Exception {
        return mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(json))
                .andReturn();
    }

    // ---------- 正常路径 ----------

    @Test
    void 发消息返回回复和自动生成的会话id() throws Exception {
        llmClient.thenAnswer("这是模型的回答");

        String body = postChat("{\"message\":\"hello\"}")
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("这是模型的回答").contains("sessionId");
    }

    @Test
    void 传入sessionId时会沿用它并保持上下文() throws Exception {
        llmClient.thenAnswer("第一轮").thenAnswer("第二轮");

        String first = postChat("{\"sessionId\":\"s-1\",\"message\":\"第一个问题\"}")
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(first).contains("\"sessionId\":\"s-1\"");

        postChat("{\"sessionId\":\"s-1\",\"message\":\"第二个问题\"}");

        // 第二次请求应该带着第一次的问答历史
        var secondRequest = llmClient.receivedRequests().get(1);
        assertThat(secondRequest.messages())
                .extracting(m -> m.content())
                .contains("第一个问题", "第一轮", "第二个问题");
    }

    @Test
    void 不传sessionId时每次生成新会话() throws Exception {
        llmClient.thenAnswer("a").thenAnswer("b");

        String first = postChat("{\"message\":\"你好\"}")
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = postChat("{\"message\":\"你好\"}")
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(first).isNotEqualTo(second);
        assertThat(sessions.count()).isEqualTo(2);
    }

    @Test
    void 中文可以正常往返() throws Exception {
        llmClient.thenAnswer("你好，我是运行在你本机的 agent。");

        String body = postChat("{\"message\":\"用中文回答我\"}")
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("你好，我是运行在你本机的 agent。");
    }

    @Test
    void historySize反映会话累积的消息数() throws Exception {
        llmClient.thenAnswer("回答");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"message\":\"问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historySize").value(3));  // system + user + assistant
    }

    // ---------- 参数校验 ----------

    @Test
    void 缺少message时返回400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void message为空白时返回400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 异常映射 ----------

    @Test
    void 模型调用失败时返回502而不是500() throws Exception {
        llmClient.thenEmptyChoices();   // Agent 会因此抛 LlmException

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("调用模型失败"));
    }

    // ---------- 辅助接口 ----------

    @Test
    void status接口返回模型与工作目录() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.model").value("test-model"));
    }

    @Test
    void sessions接口列出活跃会话() throws Exception {
        llmClient.thenAnswer("x");
        postChat("{\"sessionId\":\"kept\",\"message\":\"你好\"}");

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("kept"));
    }

    @Test
    void 删除会话后上下文被清空() throws Exception {
        llmClient.thenAnswer("x");
        postChat("{\"sessionId\":\"temp\",\"message\":\"你好\"}");

        mockMvc.perform(delete("/api/sessions/temp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true));
        assertThat(sessions.count()).isZero();
    }
}
