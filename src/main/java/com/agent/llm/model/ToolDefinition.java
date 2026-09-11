package com.agent.llm.model;

import java.util.List;
import java.util.Map;

/**
 * 告诉模型"你有哪些工具可用"。
 *
 * <p>这个结构会作为请求里的 {@code tools} 字段发出去。模型看到之后，
 * 才知道有哪些能力、每个能力要什么参数，从而决定是否调用。
 *
 * <p>序列化后的形状（OpenAI 兼容协议约定的）：
 * <pre>
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "readFile",
 *     "description": "读取指定文件的全部内容",
 *     "parameters": {
 *       "type": "object",
 *       "properties": { "path": { "type": "string", "description": "文件路径" } },
 *       "required": ["path"]
 *     }
 *   }
 * }
 * </pre>
 */
public record ToolDefinition(String type, Function function) {

    public record Function(String name, String description, Parameters parameters) {
    }

    public record Parameters(String type, Map<String, PropertySpec> properties, List<String> required) {
    }

    public record PropertySpec(String type, String description) {
    }

    /**
     * 构造一个函数型工具定义。
     *
     * @param name        工具名，模型按这个名字调用
     * @param description 给模型看的说明。**这段文字直接决定模型用不用、用得对不对**，
     *                    所以要写清楚"这个工具做什么、什么时候该用"，而不是只写个名字
     * @param properties  参数名 → 参数说明
     * @param required    必填参数名
     */
    public static ToolDefinition function(String name,
                                          String description,
                                          Map<String, PropertySpec> properties,
                                          List<String> required) {
        return new ToolDefinition("function",
                new Function(name, description, new Parameters("object", properties, required)));
    }

    /** 单参数工具的便捷写法。 */
    public static ToolDefinition singleStringParam(String name, String description,
                                                   String paramName, String paramDescription) {
        return function(name, description,
                Map.of(paramName, new PropertySpec("string", paramDescription)),
                List.of(paramName));
    }
}
