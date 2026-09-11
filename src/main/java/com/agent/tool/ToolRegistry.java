package com.agent.tool;

import com.agent.llm.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：把容器里所有 {@link AgentTool} 实现收集起来，提供按名调用。
 *
 * <p>新增工具只需写一个带 {@code @Component} 的实现类，这里会自动发现 ——
 * Spring 构造器注入 {@code List<AgentTool>} 时会把所有该类型的 bean 都塞进来。
 * 不用改注册表、不用改 {@code Agent}。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> ordered = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            ordered.put(tool.name(), tool);
        }
        this.tools = Collections.unmodifiableMap(ordered);
        log.info("已注册 {} 个工具: {}", this.tools.size(), this.tools.keySet());
    }

    /** 发给模型的工具清单。没有工具时返回空列表，请求里就不带 tools 字段。 */
    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public java.util.Set<String> toolNames() {
        return tools.keySet();
    }

    /**
     * 按名执行工具。
     *
     * <p>**任何异常都被吞掉并转成错误文本返回**，这是刻意的：工具执行失败
     * 不该让整轮对话崩掉。把错误告诉模型，它往往能自己调整（换个路径、
     * 换个关键字）并成功完成任务 —— 这是 agent 比"一问一答"更有用的地方。
     */
    public String execute(String name, String argumentsJson) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return "错误：不存在名为「%s」的工具。可用工具：%s".formatted(name, tools.keySet());
        }
        try {
            return tool.execute(argumentsJson);
        } catch (WorkspaceViolationException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("工具 {} 执行失败", name, e);
            return "错误：工具「%s」执行失败：%s".formatted(name, e.getMessage());
        }
    }
}
