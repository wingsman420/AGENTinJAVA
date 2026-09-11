package com.agent.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 解析模型给的工具参数。
 *
 * <p>模型返回的 {@code arguments} 是一段 JSON **字符串**（不是对象），所以每个工具
 * 都得先把它解析成 {@link JsonNode} 才能取参数。这段逻辑抽出来共用，
 * 顺便统一处理"参数为空"的情况 —— 模型有时会返回空串而不是 {@code {}}。
 */
final class ToolArgs {

    private ToolArgs() {
    }

    /**
     * 解析参数 JSON。
     *
     * <p>解析失败不抛异常，返回空对象：模型偶尔会给出格式不对的参数，
     * 这时候让工具按"参数缺失"继续走下去、最后返回一句有用的错误提示，
     * 比直接抛异常中断整轮对话要好。
     */
    static JsonNode parse(JsonMapper jsonMapper, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return jsonMapper.createObjectNode();
        }
        try {
            JsonNode node = jsonMapper.readTree(argumentsJson);
            return (node == null || !node.isObject()) ? jsonMapper.createObjectNode() : node;
        } catch (RuntimeException e) {
            return jsonMapper.createObjectNode();
        }
    }

    /** 取一个必填的字符串参数；缺失或空白时返回 null，由调用方给出提示。 */
    static String requiredString(JsonNode args, String name) {
        String value = args.path(name).asString("");
        return value.isBlank() ? null : value;
    }
}
