package com.agent.tool;

import com.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径沙箱：所有文件工具都必须先经过它，确保不会访问工作目录以外的文件。
 *
 * <h2>校验分两层，缺一不可</h2>
 * <ol>
 *   <li><b>词法层</b>：把用户/模型给的相对路径拼到工作目录下再 {@code normalize()}，
 *       然后看结果是否仍以工作目录开头。这能挡住 {@code ../../} 这类朴素穿越——
 *       {@code normalize()} 会把 {@code ..} 真正算掉，所以 {@code workspace/../../x}
 *       会变成工作目录之外，检查就会失败。</li>
 *   <li><b>物理层</b>：对路径调 {@code toRealPath()} 拿到真实路径后再查一次。
 *       这一层用来防**符号链接绕过** —— 工作目录里放一个指向 {@code C:\Windows} 的
 *       快捷方式，词法层看它就在目录内，但实际解析后跑到了外面。</li>
 * </ol>
 *
 * <p>只做 {@code normalize()} 不做 {@code toRealPath()} 是很常见的疏漏，
 * 在 Linux/macOS 上很容易被软链接绕过。
 */
@Component
public class WorkspaceGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceGuard.class);

    private final Path root;
    private final Path realRoot;

    public WorkspaceGuard(AgentProperties props) {
        // 根目录本身也要做一次 toRealPath，不能只用 normalize()。
        // 原因：Windows 上路径可能带 8.3 短名（如 C:\Users\ADMINI~1\...），
        // 而 toRealPath() 会展开成长名（C:\Users\Administrator\...）。
        // 如果 root 记短名、realRoot 记长名，两者就不一致，后续
        // display() 里的 relativize() 会因为前缀对不上而失败。
        // 统一成真实路径，两个字段始终一致。
        Path configured = props.workspace().toAbsolutePath().normalize();
        Path real = realPathOrNull(configured);
        this.root = (real != null) ? real : configured;
        this.realRoot = this.root;
        log.info("Agent 工作目录锁定为: {}", root);
    }

    public Path root() {
        return root;
    }

    /**
     * 把请求路径解析成绝对路径，并确认它没跑出工作目录。
     *
     * @param requested 相对工作目录的路径；null 或空串表示工作目录本身
     * @throws WorkspaceViolationException 路径越界
     */
    public Path resolve(String requested) {
        Path candidate = (requested == null || requested.isBlank())
                ? root
                : root.resolve(requested).normalize();

        // 第一层：词法检查。requested 是绝对路径时，root.resolve() 会直接返回它，
        // 于是 startsWith 失败、被拒 —— 这正是我们要的行为。
        if (!candidate.startsWith(root)) {
            throw new WorkspaceViolationException(requested, root);
        }

        // 第二层：真实路径检查，防符号链接绕过
        Path real = realPathOrNull(candidate);
        if (real == null) {
            // 文件还不存在（比如调用方只是想列出目录），词法层通过就放行，
            // 由具体工具去报"文件不存在"
            return candidate;
        }
        if (realRoot != null && !real.startsWith(realRoot)) {
            throw new WorkspaceViolationException(requested, root);
        }
        return real;
    }

    /** 非抛出式检查，用于遍历时过滤结果。 */
    public boolean isInside(Path path) {
        Path real = realPathOrNull(path);
        Path target = (real != null) ? real : path.toAbsolutePath().normalize();
        Path base = (realRoot != null) ? realRoot : root;
        return target.startsWith(base);
    }

    /** 转成相对工作目录的展示路径，让日志和给模型的输出更易读。 */
    public String display(Path path) {
        try {
            String relative = root.relativize(path.toAbsolutePath()).toString();
            // 相对路径为空说明这个路径就是工作目录本身。
            // 不能直接返回空串 —— 那样工具输出会变成"目录  的内容："，
            // 模型看到一个没有名字的目录，无法判断自己列的是哪里。
            return relative.isEmpty() ? "." : relative;
        } catch (IllegalArgumentException e) {
            // 路径与工作目录不在同一个根上（比如工作目录外的路径），
            // 退回绝对路径。display 只是给人看的辅助方法，不该成为崩溃点。
            return path.toString();
        }
    }

    private static Path realPathOrNull(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /** 保留给需要判断存在性的场景。 */
    public static boolean exists(Path p) {
        return Files.exists(p);
    }
}
