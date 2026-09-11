package com.agent.cli;

import java.io.Console;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 探测当前终端期望的字符编码。
 *
 * <h2>为什么需要它</h2>
 * Java 控制台输出乱码的根源是"写出去的编码"和"终端解码用的编码"对不上，
 * 而**不同终端用的编码不一样**：
 *
 * <table border="1">
 *   <caption>各种终端的编码</caption>
 *   <tr><th>环境</th><th>终端解码用的编码</th><th>{@code System.console()}</th></tr>
 *   <tr><td>Windows cmd / PowerShell</td><td>系统代码页（中文下是 GBK）</td><td>非 null</td></tr>
 *   <tr><td>IDEA 运行窗口</td><td>UTF-8</td><td>null</td></tr>
 *   <tr><td>Git Bash / MSYS 终端</td><td>UTF-8</td><td>null</td></tr>
 *   <tr><td>输出重定向到文件</td><td>取决于读的人，约定 UTF-8</td><td>null</td></tr>
 * </table>
 *
 * <p>所以**在代码里写死任何一种编码都是错的** —— 写死 UTF-8 会让 cmd 乱码，
 * 写死 GBK 会让 IDEA 乱码。这里改为运行时探测：
 * <ul>
 *   <li>{@code System.console()} 非空 → 说明挂在真正的系统控制台上，
 *       用 {@link Console#charset()}（它反映的就是当前代码页，
 *       用户执行过 {@code chcp 65001} 后这里也会变成 UTF-8）</li>
 *   <li>为 null → 没有真正的控制台，这些环境一律按 UTF-8 处理</li>
 * </ul>
 *
 * <p>这样三种终端都不用用户做任何设置。
 */
public final class ConsoleEncoding {

    private ConsoleEncoding() {
    }

    /** 当前终端期望的编码。永远不会返回 null。 */
    public static Charset detect() {
        Console console = System.console();
        return (console != null) ? console.charset() : StandardCharsets.UTF_8;
    }

    /** 是否挂在真正的系统控制台上（cmd / PowerShell 为 true，IDEA / Git Bash 为 false）。 */
    public static boolean hasRealConsole() {
        return System.console() != null;
    }
}
