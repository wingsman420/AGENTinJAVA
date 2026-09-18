package com.agent.llm;

import com.agent.llm.model.ChatMessage;
import com.agent.llm.model.ChatRequest;
import com.agent.llm.model.ChatResponse;
import com.agent.llm.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DeepSeek 客户端的测试。
 *
 * <p>用 {@code MockRestServiceServer} 把 HTTP 层换成假的，所以**依然不联网**。
 * 但这个测试能覆盖两类只有在这一层才测得到的东西：
 * <ol>
 *   <li>**线格式（wire format）** —— 发出去的 JSON 字段名到底对不对。
 *       协议要求 {@code max_tokens}、{@code tool_calls}、{@code tool_call_id}
 *       这种下划线写法，而 Java 侧是驼峰。字段名写错的话，真实调用时
 *       服务端只会静默忽略或报一个含糊的错，非常难查。</li>
 *   <li>**响应解析的健壮性** —— 响应里多出来的字段、被截断的回答、
 *       服务端 500，客户端都要能正确处理。</li>
 * </ol>
 */
class DeepSeekClientTest {

    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";

    private MockRestServiceServer server;
    private DeepSeekClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeepSeekClient(builder.build(), JsonMapper.builder().build());
    }

    private ChatRequest simpleRequest() {
        return ChatRequest.of("deepseek-v4-pro", List.of(ChatMessage.user("你好")), 2048);
    }

    // ---------- 请求线格式 ----------

    @Test
    void 请求体使用协议要求的下划线字段名() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "deepseek-v4-pro",
                          "max_tokens": 2048,
                          "stream": false,
                          "messages": [{"role": "user", "content": "你好"}]
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        client.chat(simpleRequest());

        server.verify();
    }

    @Test
    void 工具定义按协议格式序列化() {
        ToolDefinition tool = ToolDefinition.singleStringParam(
                "readFile", "读取文件", "path", "文件路径");
        ChatRequest request = ChatRequest.withTools(
                "deepseek-v4-pro", List.of(ChatMessage.user("读一下")), 2048, List.of(tool));

        server.expect(requestTo(ENDPOINT))
                .andExpect(content().json("""
                        {
                          "tools": [{
                            "type": "function",
                            "function": {
                              "name": "readFile",
                              "description": "读取文件",
                              "parameters": {
                                "type": "object",
                                "properties": {"path": {"type": "string", "description": "文件路径"}},
                                "required": ["path"]
                              }
                            }
                          }]
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        client.chat(request);

        server.verify();
    }

    @Test
    void 工具结果消息带tool_call_id且不带tool_calls字段() {
        List<ChatMessage> messages = List.of(
                ChatMessage.user("读文件"),
                ChatMessage.assistant("", List.of(new com.agent.llm.model.ToolCall(
                        "call_1", "function", new com.agent.llm.model.ToolCall.Function("readFile", "{}")))),
                ChatMessage.tool("call_1", "文件内容"));

        server.expect(requestTo(ENDPOINT))
                .andExpect(content().json("""
                        {
                          "messages": [
                            {"role": "user", "content": "读文件"},
                            {"role": "assistant", "content": "",
                             "tool_calls": [{"id": "call_1", "type": "function",
                                             "function": {"name": "readFile", "arguments": "{}"}}]},
                            {"role": "tool", "content": "文件内容", "tool_call_id": "call_1"}
                          ]
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        client.chat(ChatRequest.of("deepseek-v4-pro", messages, 2048));

        server.verify();
    }

    @Test
    void 没有工具时请求里不带tools字段() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(content().json("""
                        {"model": "deepseek-v4-pro"}
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        client.chat(simpleRequest());

        server.verify();
    }

    // ---------- 响应解析 ----------

    @Test
    void 解析出回答内容和思维链() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"id":"x","model":"deepseek-v4-pro",
                 "choices":[{"index":0,
                   "message":{"role":"assistant","content":"你好","reasoning_content":"用户在打招呼"},
                   "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}
                """, MediaType.APPLICATION_JSON));

        ChatResponse response = client.chat(simpleRequest());

        assertThat(response.firstMessage().content()).isEqualTo("你好");
        assertThat(response.firstMessage().reasoningContent()).isEqualTo("用户在打招呼");
        assertThat(response.firstFinishReason()).isEqualTo("stop");
        assertThat(response.isTruncated()).isFalse();
        assertThat(response.usage().totalTokens()).isEqualTo(12);
    }

    @Test
    void 解析出工具调用并且参数是待二次解析的JSON字符串() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"choices":[{"index":0,
                  "message":{"role":"assistant","content":"","tool_calls":[
                    {"id":"call_abc","type":"function",
                     "function":{"name":"listFiles","arguments":"{\\"path\\": \\".\\"}"}}]},
                  "finish_reason":"tool_calls"}]}
                """, MediaType.APPLICATION_JSON));

        ChatResponse response = client.chat(simpleRequest());

        assertThat(response.firstMessage().toolCalls()).hasSize(1);
        var call = response.firstMessage().toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_abc");
        assertThat(call.function().name()).isEqualTo("listFiles");
        // 注意这里断言的是"字符串"，正是协议的规定：要再解析一次才能拿到参数对象
        assertThat(call.function().arguments()).isEqualTo("{\"path\": \".\"}");
        assertThat(response.firstFinishReason()).isEqualTo("tool_calls");
    }

    @Test
    void 响应里的未知字段不会导致解析失败() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"id":"x","object":"chat.completion","created":1,"system_fingerprint":"abc",
                 "future_field":{"nested":true},
                 "choices":[{"index":0,"logprobs":null,
                   "message":{"role":"assistant","content":"ok","future_msg_field":1},
                   "finish_reason":"stop"}]}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.chat(simpleRequest()).firstMessage().content()).isEqualTo("ok");
    }

    @Test
    void finish_reason为length时判定为被截断() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"length"}]}
                """, MediaType.APPLICATION_JSON));

        ChatResponse response = client.chat(simpleRequest());

        assertThat(response.isTruncated()).isTrue();
        assertThat(response.firstMessage().content()).isEmpty();
    }

    // ---------- 错误处理 ----------

    @Test
    void 服务端返回500时抛出LlmException并提示可重试() {
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());

        assertThatThrownBy(() -> client.chat(simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("可以重试");
    }

    // ---------- HTTP 状态码的可诊断性 ----------
    // 这些用例的价值：如果客户端不检查状态码就直接解析响应体，
    // 所有错误都会变成一句含糊的"响应里缺少 choices"，
    // 401 和 429 的区别就丢失了，调用方也无从判断"能不能重试"。

    @Test
    void 鉴权失败时明确指出401和Key的问题() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.chat(simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("API Key");
    }

    @Test
    void 限流时明确指出429和重试建议() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.chat(simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageContaining("限流");
    }

    @Test
    void 接口路径错误时指出404并提示检查baseUrl() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.chat(simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("base-url");
    }

    @Test
    void 错误信息里带上响应体便于排查() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"invalid model\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat(simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("invalid model");
    }

    @Test
    void 响应不是合法JSON时抛出LlmException() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("这不是 JSON", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat(simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    void 空choices时firstMessage返回null由上层处理() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        ChatResponse response = client.chat(simpleRequest());

        assertThat(response.firstMessage()).isNull();
        assertThat(response.isTruncated()).isFalse();
    }

    @Test
    void 网络层失败时提示是网络问题而非状态码() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(request -> {
                    throw new java.io.IOException("connection reset");
                });

        assertThatThrownBy(() -> client.chat(simpleRequest()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("网络层");
    }
}
