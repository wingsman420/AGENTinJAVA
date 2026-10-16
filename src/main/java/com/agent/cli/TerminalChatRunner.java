package com.agent.cli;

import com.agent.config.AgentProperties;
import com.agent.conversation.ConversationService;
import com.agent.conversation.ConversationView;
import com.agent.conversation.WorkspaceView;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 终端对话入口。
 *
 * <h2>交互流程</h2>
 * <pre>
 *   选工作目录  →  选会话（恢复 / 新建 / 删除）  →  对话  →  exit
 *        ↑                                              │
 *        └──────────────  0 返回上一级  ←──────────────┘
 * </pre>
 *
 * <p>工作目录是**会话级**属性，所以每次启动先问"打开哪个目录"，
 * 再问"这个目录下的哪个对话"。同一个目录下可以有多个对话。
 *
 * <h2>为什么不需要登录</h2>
 * 终端跑在用户本机上，谁坐在电脑前就是谁。但对话同样要持久化，
 * 所以统一挂在内置用户 {@code local} 下 —— 它在首次启动时自动创建，
 * 密码是没人知道的随机值，无法从 Web 登录。
 *
 * <h2>为什么这个循环不会把 Web 服务堵死</h2>
 * Tomcat 在 {@code ApplicationRunner} 执行**之前**就已经启动完毕，
 * 请求由它自己的线程池处理。所以这里的 {@code readLine()} 一直阻塞也不影响 HTTP。
 */
@Component
@ConditionalOnProperty(name = "agent.cli.enabled", havingValue = "true", matchIfMissing = true)
public class TerminalChatRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TerminalChatRunner.class);

    /** 内置的本地用户名。终端对话全部归到这个账号下。 */
    private static final String LOCAL_USERNAME = "local";

    /** 菜单里"返回/退出"的输入。用 0 而不是 q，是为了让"数字选项"保持一致。 */
    private static final String BACK = "0";

    private static final Set<String> EXIT_COMMANDS = Set.of("exit", "quit", ":q", ":quit");
    private static final Set<String> CLEAR_COMMANDS = Set.of("clear", "/clear", ":clear");
    private static final String HELP_COMMAND = "/help";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** 列出会话时，标题过长就截断，避免菜单错位。 */
    private static final int TITLE_DISPLAY_WIDTH = 40;

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
        try {
            User local = users.findOrCreateLocalUser(LOCAL_USERNAME);
            userId = local.getId();
        } catch (Exception e) {
            printUnavailable(e);
            return;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, charset));
        printWelcome();
        try {
            mainLoop(reader, userId);
        } catch (IOException e) {
            log.warn("读取终端输入失败: {}", e.getMessage());
        }

        out.println();
        out.println("再见。Web API 仍在 http://localhost:%d/api/chat 提供服务。".formatted(serverPort));
        out.flush();
    }

    /** 主循环：选目录 → 选会话 → 对话，直到用户选择退出。 */
    private void mainLoop(BufferedReader reader, Long userId) throws IOException {
        while (true) {
            Path workspace = chooseWorkspace(reader, userId);
            if (workspace == null) {
                return;   // 用户选择退出
            }

            Long conversationId = chooseConversation(reader, userId, workspace);
            if (conversationId == null) {
                continue; // 返回上一级，重新选目录
            }

            chatLoop(reader, userId, conversationId, workspace);
        }
    }

    // ---------- 第一步：选工作目录 ----------

    /**
     * 让用户选一个工作目录。
     *
     * @return 选定的目录；返回 {@code null} 表示退出程序
     */
    private Path chooseWorkspace(BufferedReader reader, Long userId) throws IOException {
        List<WorkspaceView> workspaces = conversations.listWorkspaces(userId);

        while (true) {
            out.println();
            out.println("=== 选择工作目录 ===");
            if (workspaces.isEmpty()) {
                out.println("  （还没有用过的目录，请先输入一个）");
            } else {
                for (int i = 0; i < workspaces.size(); i++) {
                    WorkspaceView ws = workspaces.get(i);
                    out.printf("  %d) %s   （%d 个对话）%n",
                            i + 1, ws.path(), ws.conversationCount());
                }
            }
            out.printf("  %d) 输入其它路径…%n", workspaces.size() + 1);
            out.println("  0) 退出");

            int choice = readChoice(reader, workspaces.size() + 1);
            if (choice < 0) {
                continue;               // 输入不是有效数字，重来
            }
            if (choice == 0) {
                return null;            // 退出
            }
            if (choice <= workspaces.size()) {
                return Path.of(workspaces.get(choice - 1).path());
            }

            // 选了"输入其它路径"
            Path entered = promptForPath(reader);
            if (entered != null) {
                return entered;
            }
            // 用户放弃输入，回到菜单
        }
    }

    /** 提示输入一个路径并校验。返回 null 表示用户放弃（直接回车）。 */
    private Path promptForPath(BufferedReader reader) throws IOException {
        while (true) {
            out.println();
            out.println("请输入工作目录路径（直接回车返回）：");
            out.print("> ");
            out.flush();

            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            String input = line.strip().replace("\"", "");   // 容忍从资源管理器复制的带引号路径
            if (input.isEmpty()) {
                return null;
            }

            Path path = Path.of(input);
            String error = describePathProblem(path);
            if (error == null) {
                return path.toAbsolutePath().normalize();
            }
            // 校验不过就说明原因并重新询问，而不是把非法路径带进库 ——
            // 一旦写进去，之后每次打开这个会话都会失败，用户很难自己定位原因
            out.println("✗ " + error);
        }
    }

    /** 返回路径不可用的原因；可用时返回 null。 */
    private String describePathProblem(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            return "路径不存在：" + absolute;
        }
        if (!Files.isDirectory(absolute)) {
            return "这不是一个目录：" + absolute;
        }
        if (!Files.isReadable(absolute)) {
            return "目录不可读：" + absolute;
        }
        if (!props.isWorkspaceAllowed(absolute)) {
            return "该目录不在允许的范围内：" + absolute + "（见配置 agent.allowed-roots）";
        }
        return null;
    }

    // ---------- 第二步：选会话 ----------

    /**
     * 让用户选一个会话（恢复已有的、新建、或删除）。
     *
     * @return 选定的会话 id；返回 {@code null} 表示返回上一级
     */
    private Long chooseConversation(BufferedReader reader, Long userId, Path workspace)
            throws IOException {
        while (true) {
            List<ConversationView> existing = conversations.listByWorkspace(userId, workspace.toString());

            out.println();
            out.println("=== " + workspace + " 下的对话 ===");
            if (existing.isEmpty()) {
                out.println("  （这个目录下还没有对话）");
            } else {
                for (int i = 0; i < existing.size(); i++) {
                    ConversationView c = existing.get(i);
                    out.printf("  %d) 恢复  [%s]  %s   （%d 条消息）%n",
                            i + 1,
                            TIME_FORMAT.format(c.updatedAt()),
                            truncate(c.title()),
                            c.messageCount());
                }
            }
            int newIndex = existing.size() + 1;
            int deleteIndex = existing.size() + 2;
            out.printf("  %d) 新建对话%n", newIndex);
            out.printf("  %d) 删除对话%s%n", deleteIndex, existing.isEmpty() ? "（暂无可删）" : "");
            out.println("  0) 返回上一级");

            int choice = readChoice(reader, deleteIndex);
            if (choice < 0) {
                continue;
            }
            if (choice == 0) {
                return null;
            }
            if (choice <= existing.size()) {
                return existing.get(choice - 1).id();
            }
            if (choice == newIndex) {
                return createConversation(userId, workspace);
            }
            if (choice == deleteIndex && !existing.isEmpty()) {
                deleteConversation(reader, userId, existing);
                // 删除后回到菜单（列表已变，重新渲染）
            }
        }
    }

    private Long createConversation(Long userId, Path workspace) {
        ConversationView created = conversations.create(userId, "新对话", workspace);
        out.println();
        out.println("已新建对话（id " + created.id() + "）。");
        return created.id();
    }

    /** 删除会话：先选、再二次确认。 */
    private void deleteConversation(BufferedReader reader, Long userId, List<ConversationView> candidates)
            throws IOException {
        out.println();
        out.println("要删除哪个对话？");
        for (int i = 0; i < candidates.size(); i++) {
            ConversationView c = candidates.get(i);
            out.printf("  %d) [%s]  %s%n", i + 1, TIME_FORMAT.format(c.updatedAt()), truncate(c.title()));
        }
        out.println("  0) 取消");

        int choice = readChoice(reader, candidates.size());
        if (choice <= 0) {
            return;
        }
        ConversationView target = candidates.get(choice - 1);

        // 二次确认 —— 删除会连带删掉全部消息，不可逆
        out.printf("%n确认删除「%s」？该对话的全部消息都会永久删除，无法恢复。(y/N) > ", truncate(target.title()));
        out.flush();
        String answer = reader.readLine();
        if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
            out.println("已取消，未删除。");
            return;
        }

        conversations.delete(userId, target.id());
        out.println("已删除对话「" + truncate(target.title()) + "」。");
    }

    // ---------- 第三步：对话 ----------

    private void chatLoop(BufferedReader reader, Long userId, Long conversationId, Path workspace)
            throws IOException {
        printBanner(userId, conversationId, workspace);

        String line;
        while ((line = reader.readLine()) != null) {
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if (EXIT_COMMANDS.contains(input.toLowerCase())) {
                return;   // 退出程序
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

    // ---------- 输出 ----------

    private void printWelcome() {
        out.println();
        out.println("============================================================");
        out.println("  Agent 已就绪");
        out.println("  模型      : " + props.model());
        out.println("  可用工具  : " + String.join(", ", agent.activeToolNames()));
        out.println("  终端编码  : " + charset.name()
                + (ConsoleEncoding.hasRealConsole() ? "（系统控制台）" : "（无控制台，按 UTF-8）"));
        out.println("  对话会写入数据库，重启后仍在");
        out.println("============================================================");
        out.flush();
    }

    private void printBanner(Long userId, Long conversationId, Path workspace) {
        out.println();
        out.println("============================================================");
        out.println("  开始对话");
        out.println("  工作目录  : " + workspace);
        out.println("  归属用户  : " + LOCAL_USERNAME + "（用户 id " + userId
                + "，会话 id " + conversationId + "）");
        out.println("------------------------------------------------------------");
        out.println("  直接输入问题；/help 看帮助，clear 清空历史，exit 退出");
        out.println("============================================================");
        out.println();
        out.flush();
    }

    private void printHelp() {
        out.println();
        out.println("可用命令：");
        out.println("  /help    显示这段帮助");
        out.println("  clear    清空当前对话的历史（只保留 system 提示，会话 id 不变）");
        out.println("  exit     退出程序（Web API 不受影响）");
        out.println();
        out.println("可以这样问：");
        out.println("  当前目录下有哪些文件？");
        out.println("  src 目录的结构是怎样的？");
        out.println("  Agent 这个类定义在哪个文件？它做了什么？");
        out.println();
        out.flush();
    }

    private void printUnavailable(Exception e) {
        out.println();
        out.println("[终端入口不可用] 无法初始化本地用户：" + e.getMessage());
        out.println("终端对话需要数据库连接，请确认 MySQL 已启动、配置正确。");
        out.println("只想用 Web API 的话，加参数 --agent.cli.enabled=false 启动即可。");
        out.println();
        log.warn("终端入口初始化失败", e);
    }

    // ---------- 输入辅助 ----------

    /**
     * 读一个菜单选项。
     *
     * @param max 最大合法选项
     * @return 用户选的数字；输入非法时返回 -1（调用方应当重新渲染菜单）
     */
    private int readChoice(BufferedReader reader, int max) throws IOException {
        out.print("> ");
        out.flush();
        String line = reader.readLine();
        if (line == null) {
            return 0;   // stdin 关闭，当作退出
        }
        try {
            int choice = Integer.parseInt(line.strip());
            if (choice < 0 || choice > max) {
                out.println("✗ 请输入 0 ~ " + max + " 之间的数字。");
                return -1;
            }
            return choice;
        } catch (NumberFormatException e) {
            out.println("✗ 请输入数字。");
            return -1;
        }
    }

    private static String truncate(String title) {
        if (title == null || title.isBlank()) {
            return "（无标题）";
        }
        return title.length() <= TITLE_DISPLAY_WIDTH
                ? title
                : title.substring(0, TITLE_DISPLAY_WIDTH) + "…";
    }
}
