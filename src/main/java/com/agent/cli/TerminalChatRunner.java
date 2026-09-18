package com.agent.cli;

import com.agent.config.AgentProperties;
import com.agent.conversation.ConversationService;
import com.agent.core.Agent;
import com.agent.llm.LlmException;
import com.agent.user.User;
import com.agent.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Set;

/**
 * 终端对话入口。
 *
 * <h2>为什么不需要登录</h2>
 * 终端跑在用户本机上，谁坐在电脑前就是谁，再加一道登录只是徒增手续。
 * 但对话同样要持久化，所以统一挂在**内置用户 {@code local}** 下 ——
 * 它在首次启动时自动创建，密码是一个没人知道的随机值，无法从 Web 登录。
 *
 * <h2>会话从哪来</h2>
 * 每次启动接续该用户**最近活跃的那个会话**，而不是每次都开新的 ——
 * 终端的使用习惯是"接着上次聊"，不是"每次清零"。
 *
 * <h2>为什么这个循环不会把 Web 服务堵死</h2>
 * Spring Boot 的执行顺序是：先 {@code refreshContext()}（Tomcat 在这里启动完毕），
 * 再 {@code callRunners()} 执行本类的 {@code run()}。Tomcat 处理请求用的是
 * 它自己的线程池，所以这里的 {@code readLine()} 即使一直阻塞，HTTP 请求照常被服务。
 *
 * <h2>编码</h2>
 * {@code logging.charset.console} 只管日志框架，管不到 {@code System.in/out}，
 * 所以本类自己构造输入输出流。**编码不能写死** —— 见 {@link ConsoleEncoding}。
 */
@Component
@ConditionalOnProperty(name = "agent.cli.enabled", havingValue = "true", matchIfMissing = true)
public class TerminalChatRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TerminalChatRunner.class);

    /** 内置的本地用户名。终端对话全部归到这个账号下。 */
    private static final String LOCAL_USERNAME = "local";

    private static final Set<String> EXIT_COMMANDS = Set.of("exit", "quit", ":q", ":quit");
    private static final Set<String> CLEAR_COMMANDS = Set.of("clear", "/clear", ":clear");
    private static final String HELP_COMMAND = "/help";

    private final Agent agent;
    private final ConversationService conversations;
    private final UserService users;
    private final AgentProperties props;
    private final int serverPort;

    /** 探测出来的终端编码，输入输出都用它。 */
    private final Charset charset = ConsoleEncoding.detect();
    private final PrintStream out = createOut(charset);

    public TerminalChatRunner(Agent agent, ConversationService conversations, UserService users,
                              AgentProperties props,
                              @Value("${server.port:8080}") int serverPort) {
        this.agent = agent;
        this.conversations = conversations;
        this.users = users;
        this.props = props;
        this.serverPort = serverPort;
    }

    private static PrintStream createOut(Charset charset) {
        return new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
    }

    @Override
    public void run(ApplicationArguments args) {
        Long userId;
        Long conversationId;
        try {
            User local = users.findOrCreateLocalUser(LOCAL_USERNAME);
            userId = local.getId();
            conversationId = conversations.findLatestOrCreate(userId, "终端会话");
        } catch (Exception e) {
            // 终端入口依赖数据库。连不上时给一句明确的话，而不是让整个应用带着
            // 一堆栈信息崩掉 —— 毕竟 Web 部分可能还能用。
            out.println();
            out.println("[终端入口不可用] 无法初始化本地用户或会话：" + e.getMessage());
            out.println("终端对话需要数据库连接，请确认 MySQL 已启动、配置正确。");
            out.println("只想用 Web API 的话，加参数 --agent.cli.enabled=false 启动即可。");
            out.println();
            log.warn("终端入口初始化失败", e);
            return;
        }

        printBanner(userId, conversationId);

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
                    conversations.clearMessages(userId, conversationId);
                    out.println("历史已清空，当前只保留 system 提示（会话 id 不变：" + conversationId + "）。");
                    continue;
                }
                if (HELP_COMMAND.equals(input)) {
                    printHelp();
                    continue;
                }
                handleOnce(userId, conversationId, input);
            }
        } catch (IOException e) {
            log.warn("读取终端输入失败: {}", e.getMessage());
        }

        out.println();
        out.println("终端对话已结束。Web API 仍在 http://localhost:%d/api/chat 提供服务。"
                .formatted(serverPort));
        out.flush();
    }

    private void handleOnce(Long userId, Long conversationId, String input) {
        out.println();
        try {
            ConversationService.ChatResult result = conversations.chat(userId, conversationId, input);
            out.println(result.reply());
        } catch (LlmException e) {
            // 只提示，不退出循环 —— 一次调用失败（网络抖动、限流）不该终止整个会话
            out.println("[调用模型失败] " + e.getMessage());
            log.warn("调用模型失败", e);
        } catch (Exception e) {
            out.println("[出错] " + e.getMessage());
            log.warn("处理失败", e);
        }
        out.println();
        out.flush();
    }

    private void printBanner(Long userId, Long conversationId) {
        out.println();
        out.println("============================================================");
        out.println("  Agent 已就绪");
        out.println("  模型      : " + props.model());
        out.println("  工作目录  : " + props.workspace());
        out.println("  可用工具  : " + String.join(", ", agent.activeToolNames()));
        out.println("  对话归属  : " + LOCAL_USERNAME + "（用户 id " + userId
                + "，会话 id " + conversationId + "）");
        out.println("  终端编码  : " + charset.name()
                + (ConsoleEncoding.hasRealConsole() ? "（系统控制台）" : "（无控制台，按 UTF-8）"));
        out.println("  Web API   : http://localhost:" + serverPort + "/api/chat");
        out.println("------------------------------------------------------------");
        out.println("  直接输入问题开始对话；输入 /help 看帮助，输入 exit 退出");
        out.println("  对话会写入数据库，重启后仍在");
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
