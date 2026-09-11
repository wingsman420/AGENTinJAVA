package com.agent.llm.model;

/**
 * 模型请求调用某个工具。
 *
 * <p>两种场景都会用到这个类：
 * <ul>
 *   <li>**响应**里：模型说"我要调 listFiles，参数是 {...}"</li>
 *   <li>**请求**里：把模型上一轮的这个决定原样回传，它才知道自己之前要干什么</li>
 * </ul>
 *
 * <p>注意 {@code function.arguments} 是一个 **JSON 字符串**，不是 JSON 对象。
 * 也就是说它需要**二次解析**才能拿到真正的参数 —— 这是 OpenAI 兼容协议的规定，
 * 也是新手最容易踩的坑：直接当成 Map 用会拿到一整个字符串。
 */
public record ToolCall(String id, String type, Function function) {

    /** 内嵌类型，名字与协议里的字段对齐；注意不要和 java.util.function.Function 搞混。 */
    public record Function(String name, String arguments) {
    }
}
