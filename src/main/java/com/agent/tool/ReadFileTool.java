package com.agent.tool;

import com.agent.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 工具：读取文件内容。
 *
 * <p>这是"让 agent 理解本地文件"的核心工具。输出带行号，因为模型要能
 * 准确引用"第 42 行定义了这个方法"，才能给出有用的回答。
 *
 * <p>有两道保护：文件太大的话只读前面一部分并明确告知被截断 ——
 * 不然一个几十兆的日志文件会瞬间把上下文撑爆，既浪费钱又会让模型
 * 丢失前面聊过的内容。
 */
@Component
public class ReadFileTool implements AgentTool {

    /** 单次读取的字节上限。超过就截断，避免撑爆模型的上下文窗口。 */
    private static final int MAX_BYTES = 256 * 1024;

    private final WorkspaceGuard guard;
    private final JsonMapper jsonMapper;

    public ReadFileTool(WorkspaceGuard guard, JsonMapper jsonMapper) {
        this.guard = guard;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.function(
                "readFile",
                "读取指定文件的全部文本内容，返回结果带行号。"
                        + "不确定文件路径时，先用 listFiles 查看目录结构。",
                Map.of("path", new ToolDefinition.PropertySpec("string",
                        "文件路径，相对于工作目录")),
                List.of("path"));
    }

    @Override
    public String doExecute(String argumentsJson) {
        JsonNode args = ToolArgs.parse(jsonMapper, argumentsJson);
        String pathArg = ToolArgs.requiredString(args, "path");
        if (pathArg == null) {
            return "错误：缺少必填参数 path。请给出要读取的文件路径，例如 \"src/main/java/com/agent/Agent.java\"。";
        }

        Path file = guard.resolve(pathArg);

        if (!Files.exists(file)) {
            return "错误：文件不存在：%s。可以用 listFiles 查看该目录下实际有哪些文件。"
                    .formatted(guard.display(file));
        }
        if (Files.isDirectory(file)) {
            return "错误：%s 是一个目录而不是文件。用 listFiles 列出它的内容。"
                    .formatted(guard.display(file));
        }

        try {
            if (TextReader.looksBinary(file)) {
                return "错误：%s 看起来是二进制文件（可能是图片、压缩包或编译产物），无法作为文本读取。"
                        .formatted(guard.display(file));
            }

            long size = Files.size(file);
            byte[] bytes = Files.readAllBytes(file);
            boolean truncated = bytes.length > MAX_BYTES;
            if (truncated) {
                bytes = java.util.Arrays.copyOf(bytes, MAX_BYTES);
            }

            String text = TextReader.decode(bytes);
            return render(guard.display(file), text, size, truncated);
        } catch (IOException e) {
            return "错误：读取文件失败：%s".formatted(e.getMessage());
        }
    }

    /** 加行号渲染。行号让模型能准确定位，也让用户能核对模型说的是不是真的。 */
    private static String render(String displayPath, String text, long sizeBytes, boolean truncated) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("文件 ").append(displayPath)
                .append("（").append(sizeBytes).append(" 字节，").append(lines.length).append(" 行）:\n");

        int width = String.valueOf(lines.length).length();
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%" + width + "d | %s%n", i + 1, stripTrailingCr(lines[i])));
        }

        if (truncated) {
            sb.append("\n[注意] 文件过大，只显示了前 ").append(MAX_BYTES)
                    .append(" 字节。如需查看剩余内容，请用 searchCode 定位具体位置。\n");
        }
        return sb.toString();
    }

    /** Windows 换行是 \r\n，按 \n 切完每行末尾会留个 \r，去掉它免得混进模型看到的文本。 */
    private static String stripTrailingCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
