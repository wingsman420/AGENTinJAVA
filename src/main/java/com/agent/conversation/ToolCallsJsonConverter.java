package com.agent.conversation;

import com.agent.llm.model.ToolCall;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * 把 {@code List<ToolCall>} 存成一个 JSON 字符串列。
 *
 * <h2>为什么需要它</h2>
 * {@code ToolCall} 是 record，而 JPA 实体不能是 record（Hibernate 显式跳过 record 的
 * 字节码增强）。但**实体里的字段可以是 record 或其集合** —— Hibernate 判定"是否集合映射"
 * 只看 {@code @OneToMany}/{@code @ElementCollection} 这类注解，不看 Java 类型。
 * 所以这里用 {@link AttributeConverter} 把它当普通标量属性处理，落到一个 TEXT 列。
 *
 * <p>这样做的好处是 {@code ChatMessage} 与 {@code ToolCall} 这两个 record
 * **在 API 层和持久层共用同一套模型**，不需要维护"API 用的 record"和"实体用的 POJO"
 * 两套结构再互相转换。
 *
 * <h2>为什么用静态 JsonMapper 而不是注入 Spring 的 Bean</h2>
 * {@code AttributeConverter} 由 Hibernate 实例化，不是 Spring Bean。想注入需要额外配置
 * {@code SpringBeanContainer}。{@code JsonMapper} 本身线程安全且可复用，用一个静态实例
 * 更简单，也少一层装配风险。
 */
@Converter
public class ToolCallsJsonConverter implements AttributeConverter<List<ToolCall>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 反序列化泛型集合必须保留泛型信息，不能用 {@code readValue(json, List.class)}。 */
    private static final TypeReference<List<ToolCall>> TOOL_CALL_LIST = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<ToolCall> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;   // 非工具调用消息存 NULL，而不是 "[]"
        }
        return MAPPER.writeValueAsString(attribute);
    }

    @Override
    public List<ToolCall> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return MAPPER.readValue(dbData, TOOL_CALL_LIST);
    }
}
