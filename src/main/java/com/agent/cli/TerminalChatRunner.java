package com.agent.cli;

import com.agent.config.AgentProperties;
import com.agent.core.Agent;
import com.agent.core.ChatSession;
import com.agent.core.SessionStore;
import com.agent.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 终端对话入口：应用启动完成后，在主线程开一个 stdin 循环。
 *
 * <h2>为什么这个循环不会把 Web 服务堵死</h2>
 * Spring Boot 的执行顺序是：先 {@code refreshContext()}（Tomcat 在这里启动完毕），
 * 再 {@code callRunners()} 执行本类的 {@code run()}。而 Tomcat 处理请求用的是
 * 它自己的线程池，所以这里的 {@code readLine()} 即使一直阻塞，HTTP 请求照常被服务。
 *
 * <h2>stdin 关闭时会怎样</h2>
 * {@code readLine()} 返回 null，循环退出，{@code run()} 返回。但 **JVM 不会退出** ——
 * Spring Boot 为 Tomcat 专门创建了一个非守护线程（{@code container-N}）撑着进程。
 * 这正好是服务器部署需要的行为：stdin 是 /dev/null，终端入口自动失效，
 * Web API 继续提供服务。
 *
 * <h2>为什么不用 System.console() 判断是否交互式</h2>
 * 因为 IDEA 的运行控制台不是真正的终端，{@code System.console()} 在那里返回 null。
 * 用它做判断会导致"在 IDEA 里跑不出终端对话"——而 IDEA 恰恰是最主要的开发场景。
 * 所以这里改用配置项 {@code agent.cli.enabled} 控制（默认开）。
 *
 * <h2>编码</h2>
 * {@code logging.charset.console} 只管日志框架，管不到 {@code System.in/out}，
 * 所以本类自己构造输入输出流。**关键是编码不能在代码里写死** ——
 * 原生 Windows 控制台用系统代码页（中文下是 GBK），而 IDEA 控制台和 Git Bash 用 UTF-8，
 * 写死任何一种都会在另一种终端里乱码。这里改为运行时探测，详见 {@link #consoleCharset()}。
 */
@Component
@ConditionalOnProperty(name = "agent.cli.enabled", havingValue = "true", matchIfMissing = true)
public class TerminalChatRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TerminalChatRunner.class);

    private static final Set<String> EXIT_COMMANDS = Set.of("exit", "quit", ":q", ":quit");
    private static final Set<String> CLEAR_COMMANDS = Set.of("clear", "/clear", ":clear");
    private static final String HELP_COMMAND = "/help";

    /** 终端会话固定用这个 id，于是整个进程生命周期内上下文是连续的。 */
    private static final String LOCAL_SESSION_ID = "local-terminal";

    private final Agent agent;
    private final SessionStore sessions;
    private final AgentProperties props;
    private final int serverPort;

    /** 探测出来的终端编码，输入输出都用它，详见 {@link ConsoleEncoding}。 */
    private final Charset charset = ConsoleEncoding.detect();
    private final PrintStream out = createOut(charset);

    public TerminalChatRunner(Agent agent, SessionStore sessions, AgentProperties props,
                              @Value("${server.port:8080}") int serverPort) {
        this.agent = agent;
        this.sessions = sessions;
        this.props = props;
        this.serverPort = serverPort;
    }

    private static PrintStream createOut(Charset charset) {
        return new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
    }

    @Override
    public void run(ApplicationArguments args) {
        ChatSession session = sessions.getOrCreate(LOCAL_SESSION_ID);
        printBanner();

        // 输入也用同一个探测出来的编码，否则中文提问会乱码
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, charset));

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String input = line.strip();
                if (input.isEmpty()) {
                    continue;
                }
                if (EXIT_COMMANDS.contains(input.toLowerCase())) {
                    break;
                }
                if (CLEAR_COMMANDS.contains(input.toLowerCase())) {
                    session.clear();
                    out.println("历史已清空，当前只保留 system 提示（会话 id 不变：" + session.id() + "）。");
                    continue;
                }
                if (HELP_COMMAND.equals(input)) {
                    printHelp();
                    continue;
                }
                handleOnce(session, input);
            }
        } catch (IOException e) {
            log.warn("读取终端输入失败: {}", e.getMessage());
        }

        out.println();
        out.println("终端对话已结束。Web API 仍在 http://localhost:%d/api/chat 提供服务。"
                .formatted(serverPort));
        out.flush();
    }

    private void handleOnce(ChatSession session, String input) {
        out.println();
        try {
            String reply = agent.chat(session, input);
            out.println(reply);
        } catch (LlmException e) {
            // 只提示，不退出循环 —— 一次调用失败（网络抖动、限流）不该终止整个会话
            out.println("[调用模型失败] " + e.getMessage());
            log.warn("调用模型失败", e);
        }
        out.println();
        out.flush();
    }

    private void printBanner() {
        out.println();
        out.println("============================================================");
        out.println("  Agent 已就绪");
        out.println("  模型      : " + props.model());
        out.println("  工作目录  : " + props.workspace());
        out.println("  可用工具  : " + String.join(", ", agent.activeToolNames()));
        // 把探测到的编码显示出来：中文一旦乱码，第一眼就能看出是不是编码没对上
        out.println("  终端编码  : " + charset.name()
                + (ConsoleEncoding.hasRealConsole() ? "（系统控制台）" : "（无控制台，按 UTF-8）"));
        out.println("  Web API   : http://localhost:" + serverPort + "/api/chat");
        out.println("------------------------------------------------------------");
        out.println("  直接输入问题开始对话；输入 /help 看帮助，输入 exit 退出");
        out.println("============================================================");
        out.println();
        out.flush();
    }

    private void printHelp() {
        out.println();
        out.println("可用命令：");
        out.println("  /help    显示这段帮助");
        out.println("  clear    清空对话历史（只保留 system 提示，会话 id 不变）");
        out.println("  exit     退出终端对话（Web API 不受影响）");
        out.println();
        out.println("可以这样问：");
        out.println("  当前目录下有哪些文件？");
        out.println("  src 目录的结构是怎样的？");
        out.println("  Agent 这个类定义在哪个文件？它做了什么？");
        out.println();
        out.flush();
    }
}
