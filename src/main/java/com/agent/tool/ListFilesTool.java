package com.agent.tool;

import com.agent.llm.model.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工具：列出目录下的文件与子目录。
 *
 * <p>这是 agent "理解本地文件"的入口 —— 它得先能看见目录里有什么，
 * 才知道下一步该读哪个文件。所以 description 里要写清楚"想知道项目结构时先用这个"，
 * 否则模型可能一上来就盲目调 readFile 猜文件名。
 */
@Component
public class ListFilesTool implements AgentTool {

    /** 返回条数上限：目录太大时全量返回会撑爆上下文，也会浪费 token。 */
    private static final int MAX_ENTRIES = 200;

    /** 这类目录对"理解代码"没帮助（构建产物、依赖、IDE 元数据），遍历时跳过。 */
    private static final Set<String> SKIP_DIRS =
            Set.of(".git", "target", "node_modules", ".idea", "out", "build", ".gradle", ".mvn", ".venv");

    /** 单层模式：目录在前、文件在后，各自按名字排。 */
    private static final Comparator<Path> DIR_FIRST_BY_NAME =
            Comparator.comparing((Path p) -> !Files.isDirectory(p))
                    .thenComparing(p -> p.getFileName().toString().toLowerCase());

    private final WorkspaceGuard guard;
    private final JsonMapper jsonMapper;

    public ListFilesTool(WorkspaceGuard guard, JsonMapper jsonMapper) {
        this.guard = guard;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.function(
                "listFiles",
                "列出指定目录下的文件和子目录。想了解项目结构、或者不确定某个文件叫什么名字时，先用这个工具看一眼。",
                java.util.Map.of(
                        "path", new ToolDefinition.PropertySpec("string",
                                "目录路径，相对于工作目录。用 \".\" 表示工作目录根目录"),
                        "recursive", new ToolDefinition.PropertySpec("boolean",
                                "是否递归列出所有层级的子目录，默认 false 只列一层")),
                List.of("path"));
    }

    @Override
    public String doExecute(String argumentsJson) {
        JsonNode args = ToolArgs.parse(jsonMapper, argumentsJson);
        String pathArg = args.path("path").asString(".");
        boolean recursive = args.path("recursive").asBoolean(false);

        Path dir = guard.resolve(pathArg);

        if (!Files.exists(dir)) {
            return "错误：目录不存在：%s".formatted(guard.display(dir));
        }
        if (!Files.isDirectory(dir)) {
            return "错误：%s 是一个文件而不是目录。要读文件内容请用 readFile 工具。"
                    .formatted(guard.display(dir));
        }

        // 递归模式下必须按**完整相对路径**排序，不能按文件名排。
        // 按文件名排会把 src\main\... 和 src\test\... 打散混在一起，完全看不出层级；
        // 按完整路径排出来才是自然的树形顺序。
        Comparator<Path> order = recursive
                ? Comparator.comparing(p -> guard.display(p).toLowerCase())
                : DIR_FIRST_BY_NAME;

        List<Path> entries;
        try (Stream<Path> stream = recursive ? Files.walk(dir) : Files.list(dir)) {
            entries = stream
                    .filter(p -> !p.equals(dir))
                    .filter(guard::isInside)                 // 符号链接指到外面的，过滤掉
                    .filter(p -> !isInSkippedDir(dir, p))
                    .sorted(order)
                    .limit(MAX_ENTRIES + 1L)
                    .toList();
        } catch (IOException e) {
            return "错误：列出目录失败：" + e.getMessage();
        }

        if (entries.isEmpty()) {
            return "目录 %s 是空的。".formatted(guard.display(dir));
        }

        boolean truncated = entries.size() > MAX_ENTRIES;
        if (truncated) {
            entries = entries.subList(0, MAX_ENTRIES);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("目录 ").append(guard.display(dir)).append(" 的内容：\n");
        for (Path p : entries) {
            String relative = guard.display(p);
            if (recursive) {
                // 递归模式下用相对路径，平铺列出，层级靠路径本身体现
                sb.append(Files.isDirectory(p) ? "  [目录] " : "  [文件] ").append(relative).append('\n');
            } else {
                String name = p.getFileName().toString();
                sb.append(Files.isDirectory(p) ? "  [目录] " : "  [文件] ")
                        .append(name)
                        .append(Files.isDirectory(p) ? "/" : "")
                        .append('\n');
            }
        }
        if (truncated) {
            sb.append("  ... 条目过多，只显示了前 ").append(MAX_ENTRIES)
                    .append(" 项。建议缩小范围，或改用 searchCode 按关键字定位。\n");
        }
        return sb.toString();
    }

    /** 判断路径是否落在要跳过的目录里（只看相对工作目录的部分，避免误伤外层同名目录）。 */
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
