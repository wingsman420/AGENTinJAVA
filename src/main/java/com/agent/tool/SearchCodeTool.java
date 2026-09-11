package com.agent.tool;

import com.agent.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工具：在文件内容里搜索关键字。
 *
 * <p>补上 {@code listFiles} 和 {@code readFile} 之间的空档：想知道
 * "哪个文件定义了 ChatController"、"哪里用到了 workspace" 时，
 * 逐个大文件去读代价太高，按关键字直接定位才是对的做法。
 *
 * <p>与 {@code listFiles}/{@code readFile} 一样接收 {@code path} 参数，
 * 三个工具参数形状保持一致。
 */
@Component
public class SearchCodeTool implements AgentTool {

    private static final int MAX_MATCHES = 100;
    private static final int MAX_FILES_SCANNED = 2000;
    private static final int MAX_LINE_LENGTH = 300;

    private static final Set<String> SKIP_DIRS =
            Set.of(".git", "target", "node_modules", ".idea", "out", "build", ".gradle", ".mvn", ".venv");

    private final WorkspaceGuard guard;
    private final JsonMapper jsonMapper;

    public SearchCodeTool(WorkspaceGuard guard, JsonMapper jsonMapper) {
        this.guard = guard;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.function(
                "searchCode",
                "在指定目录（含所有子目录）的文件内容中搜索关键字，返回匹配的文件路径、行号和该行内容。"
                        + "适合定位某个类、方法或变量在哪些地方被定义和使用。注意它搜索的是文件内容，不是文件名。",
                Map.of(
                        "path", new ToolDefinition.PropertySpec("string",
                                "要搜索的目录，相对于工作目录。用 \".\" 表示工作目录根目录"),
                        "keyword", new ToolDefinition.PropertySpec("string",
                                "要搜索的关键字，区分大小写")),
                List.of("path", "keyword"));
    }

    @Override
    public String doExecute(String argumentsJson) {
        JsonNode args = ToolArgs.parse(jsonMapper, argumentsJson);
        String pathArg = ToolArgs.requiredString(args, "path");
        String keyword = ToolArgs.requiredString(args, "keyword");

        if (pathArg == null || keyword == null) {
            return "错误：path 和 keyword 都是必填参数。例如 {\"path\": \".\", \"keyword\": \"Agent\"}。";
        }

        Path start = guard.resolve(pathArg);
        if (!Files.exists(start)) {
            return "错误：路径不存在：%s".formatted(guard.display(start));
        }

        List<Path> files;
        try (Stream<Path> stream = Files.walk(start)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(guard::isInside)
                    .filter(p -> !isInSkippedDir(start, p))
                    .limit(MAX_FILES_SCANNED)
                    .toList();
        } catch (IOException e) {
            return "错误：遍历目录失败：" + e.getMessage();
        }

        List<String> matches = new ArrayList<>();
        int scanned = 0;
        boolean hitLimit = false;

        outer:
        for (Path file : files) {
            if (TextReader.looksBinary(file)) {
                continue;
            }
            List<String> lines;
            try {
                String text = TextReader.read(file);
                lines = List.of(text.split("\n", -1));
            } catch (IOException e) {
                continue; // 单个文件读不了就跳过，不影响整体搜索
            }
            scanned++;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains(keyword)) {
                    if (matches.size() >= MAX_MATCHES) {
                        hitLimit = true;
                        break outer;
                    }
                    matches.add("%s:%d: %s".formatted(
                            guard.display(file), i + 1, truncate(line.strip())));
                }
            }
        }

        if (matches.isEmpty()) {
            return "在 %s 下扫描了 %d 个文件，没有找到包含「%s」的内容。"
                    .formatted(guard.display(start), scanned, keyword);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("在 ").append(guard.display(start)).append(" 下扫描 ")
                .append(scanned).append(" 个文件，找到 ")
                .append(matches.size()).append(" 处匹配「").append(keyword).append("」：\n");
        for (String m : matches) {
            sb.append("  ").append(m).append('\n');
        }
        if (hitLimit) {
            sb.append("  ... 匹配过多，只显示前 ").append(MAX_MATCHES)
                    .append(" 处。建议换更具体的关键字。\n");
        }
        return sb.toString();
    }

    private static String truncate(String line) {
        return line.length() <= MAX_LINE_LENGTH
                ? line
                : line.substring(0, MAX_LINE_LENGTH) + "...";
    }

    private boolean isInSkippedDir(Path base, Path candidate) {
        try {
            for (Path part : base.relativize(candidate)) {
                if (SKIP_DIRS.contains(part.toString())) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
