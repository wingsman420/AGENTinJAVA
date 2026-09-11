package com.agent.tool;

import com.agent.TestFixtures;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路径沙箱的测试。这是整个项目**安全上最关键**的一块：
 * 一旦这里漏了，被提示注入诱导的模型就能读到工作目录之外的任意文件
 * （~/.ssh/id_rsa、浏览器 cookie、云服务凭证……）。
 */
class WorkspaceGuardTest {

    @TempDir
    Path workspace;

    private WorkspaceGuard guard;

    @BeforeEach
    void setUp() {
        guard = new WorkspaceGuard(TestFixtures.props(workspace));
    }

    // ---------- 正常路径 ----------

    @Test
    void 空参数解析为工作目录本身() {
        assertThat(guard.resolve(null)).isEqualTo(guard.root());
        assertThat(guard.resolve("")).isEqualTo(guard.root());
        assertThat(guard.resolve("   ")).isEqualTo(guard.root());
    }

    @Test
    void 点号解析为工作目录本身() {
        assertThat(guard.resolve(".")).isEqualTo(guard.root());
    }

    @Test
    void 工作目录内的路径正常解析() throws IOException {
        Path sub = Files.createDirectories(workspace.resolve("src/main"));
        assertThat(guard.resolve("src/main")).isEqualTo(sub.toRealPath());
    }

    @Test
    void 内部路径以点点开头也能正常解析() throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/a.txt"), "hi");

        assertThat(guard.resolve("src/../src/a.txt")).isEqualTo(workspace.resolve("src/a.txt").toRealPath());
    }

    // ---------- 越界拦截 ----------

    @Test
    void 用上级目录穿越被拒绝() {
        assertThatThrownBy(() -> guard.resolve("../outside.txt"))
                .isInstanceOf(WorkspaceViolationException.class)
                .hasMessageContaining("超出了 agent 的工作目录");
    }

    @Test
    void 连续多级穿越被拒绝() {
        assertThatThrownBy(() -> guard.resolve("../../../../../../etc/passwd"))
                .isInstanceOf(WorkspaceViolationException.class);
    }

    @Test
    void 先进入子目录再穿越出去也被拒绝() {
        assertThatThrownBy(() -> guard.resolve("src/../../outside.txt"))
                .isInstanceOf(WorkspaceViolationException.class);
    }

    @Test
    void 绝对路径指向工作目录之外被拒绝() {
        Path outside = workspace.getParent().resolve("elsewhere.txt");
        assertThatThrownBy(() -> guard.resolve(outside.toString()))
                .isInstanceOf(WorkspaceViolationException.class);
    }

    @Test
    void Windows盘符路径也被拒绝() {
        assertThatThrownBy(() -> guard.resolve("C:\\Windows\\System32\\drivers\\etc\\hosts"))
                .isInstanceOf(WorkspaceViolationException.class);
    }

    // ---------- 符号链接绕过 ----------

    @Test
    void 符号链接指向工作目录外时被拒绝() throws IOException {
        Path outsideDir = Files.createTempDirectory("agent-guard-outside");
        Path link = workspace.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows 默认需要管理员权限或开发者模式才能建符号链接
            Assumptions.assumeTrue(false, "当前系统无法创建符号链接，跳过该用例: " + e.getMessage());
        }

        try {
            // 关键：词法上 escape-link 就在工作目录里，只有解析真实路径才能发现它指向外面
            assertThatThrownBy(() -> guard.resolve("escape-link"))
                    .isInstanceOf(WorkspaceViolationException.class);
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outsideDir);
        }
    }

    // ---------- isInside ----------

    @Test
    void isInside对工作目录内的路径返回true() throws IOException {
        Path inside = Files.createFile(workspace.resolve("a.txt"));
        assertThat(guard.isInside(inside)).isTrue();
    }

    @Test
    void isInside对工作目录外的路径返回false() {
        assertThat(guard.isInside(workspace.getParent())).isFalse();
    }

    // ---------- display ----------

    @Test
    void display返回相对路径便于阅读() {
        // 必须用 guard.root() 拼路径，不能用 @TempDir 给的路径：
        // Windows 上 @TempDir 可能是 8.3 短名（C:\Users\ADMINI~1\...），
        // 而 guard 内部统一成了真实长名，两者前缀对不上就相对不出来了。
        // 实际运行时传给 display() 的都是 resolve() 产出的真实路径，所以这里有代表性。
        assertThat(guard.display(guard.root().resolve("src/Main.java")))
                .isEqualTo(Path.of("src", "Main.java").toString());
    }

    @Test
    void display遇到工作目录外的路径时降级为绝对路径而不抛异常() {
        // display 只是给人看的辅助方法，它不该成为新的崩溃点
        assertThat(guard.display(workspace.getParent())).isNotBlank();
    }

    @Test
    void display对工作目录本身返回点号而不是空串() {
        // 这是个真实踩过的坑：relativize 自己得到空路径，
        // 于是工具输出变成"目录  的内容："—— 模型看到一个没名字的目录，
        // 无法判断自己列的是哪里。
        assertThat(guard.display(guard.root())).isEqualTo(".");
    }

    @Test
    void 越界异常的提示信息包含工作目录位置() {
        assertThatThrownBy(() -> guard.resolve("../x"))
                .hasMessageContaining(guard.root().toString());
    }
}
