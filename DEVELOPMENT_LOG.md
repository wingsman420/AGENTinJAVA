# 开发日志：agent-cli

把原先的 HelloWorld 项目改造成一个能调用 DeepSeek API 的本地 Agent。

- **起点**：`com.training:lab1` —— Spring Boot HelloWorld（`Lab1Application` + Hello 三件套）
- **终点**：`com.agent:agent-cli:0.1.0` —— 终端对话 + Web API + 本地文件理解
- **日期**：2026-09-11

---

## 一、需求与拆解

原始需求：

1. 本地终端能对话，同时能通过 Web API 远程对话
2. Agent 能理解本地文件
3. 去掉 `hello`、`training` 这类命名

第 1、2 条其实是一条完整的链路：**Agent 要知道文件里有什么，得先能主动去读；能主动读，就得有工具调用循环（ReAct）**。所以拆成三层来做：

| 层 | 职责 | 对应实现 |
|---|---|---|
| LLM 层 | 跟模型通信，屏蔽协议细节 | `llm/` |
| 工具层 | 让模型能看见本地文件 | `tool/` |
| 编排层 | 驱动"想 → 做 → 看结果 → 再想"的循环 | `core/Agent.java` |
| 入口层 | 终端 + Web 两个入口，共用上面三层 | `cli/` `web/` |

两个入口共用同一套 `Agent` 和 `SessionStore`，所以业务逻辑只写了一遍，而且**终端里聊到一半可以切到浏览器用同一个 sessionId 接着聊**。

---

## 二、动手前的验证

这个项目有个特点：**很多假设错了不会立刻报错，而是到跑起来才莫名其妙地失败**。所以动手前先把不确定的地方实测了一遍。

### 2.1 实测 DeepSeek API

没有查文档，而是直接打接口看真实响应。结果发现一条**决定性的信息**：

```
$ curl ... -d '{"model":"deepseek-v4-pro", ... "max_tokens":20}'
{"choices":[{"message":{"role":"assistant","content":""},
             "finish_reason":"length"}],
 "usage":{"completion_tokens_details":{"reasoning_tokens":20}}}
```

**`deepseek-v4-pro` 是推理模型** —— 回答前先输出一段思维链（响应里的 `reasoning_content`），而思维链**同样消耗 token 预算**。`max_tokens` 给 20 时，思维链就把预算吃光了，`content` 返回空字符串。

这个如果不提前发现，会表现为"Agent 有时候回答是空的"，非常难查。据此确定了三件事：

- 默认 `max_tokens` 设为 2048
- 客户端要识别 `finish_reason == "length"` 并给出可操作的提示，而不是把空字符串丢给用户
- **思维链不写入对话历史**（内部推理过程，回传只会白白烧 token）

同时验证了工具调用格式（`finish_reason: "tool_calls"`，`arguments` 是需要二次解析的 JSON 字符串），以及完整的 ReAct 回路可以跑通。

### 2.2 核实 Spring Boot 4 的 API

我对 Spring Boot 4 的部分记忆是 **Spring Boot 3 时代的**，直接照着写会踩坑。派了一个子任务去实际解压 jar、读字节码核实，结果发现 6 处已经变了：

| 项 | 我以为的 | 实际的 |
|---|---|---|
| JSON | `com.fasterxml.jackson.databind.ObjectMapper` | **Jackson 3**：`tools.jackson.databind.json.JsonMapper` |
| 取节点值 | `node.asText()` | `node.asString()`（`asText` 已废弃） |
| Mock Bean | `@MockBean` | **已删除**，改用 `@MockitoBean`，且包名搬到 `org.springframework.test.context.bean.override.mockito` |
| 测试框架 | JUnit 5 | **JUnit 6.0.3** |
| RestClient | `@Autowired RestClient.Builder` | **会启动失败** —— 自动配置在独立的 `spring-boot-starter-restclient` 模块里，不在依赖图内 |
| 取响应体 | `.body(X.class)` | `.requiredBody(X.class)`（Spring 7 新增，null 时抛异常） |

其中 `RestClient.Builder` 那条尤其值得记：**它不会报"找不到 bean"，而是直接启动失败**，错误信息不会指向真正的原因。改成自己 `RestClient.builder()` 建就没问题。

---

## 三、关键设计决策

### 3.1 把 `LlmClient` 抽成接口 —— 为了测试

`Agent` 里装的是整个项目最核心的逻辑（ReAct 循环）。如果它直接依赖 `DeepSeekClient`，那每次测试都要真的联网调 API：慢、花钱、网络一抖测试就红、还没法构造"模型连续调三轮工具"这类边界场景。

抽成接口后，测试注入 `FakeLlmClient` 按脚本返回响应。**最终 80 个测试全部离线运行，不消耗一分钱余额**，而且覆盖了真实 API 很难复现的情况。

这是"依赖抽象而非实现"在这个项目里最直接的收益。

### 3.2 路径沙箱：两层校验

Agent 读到的一切都会发到 DeepSeek 的服务器。所以文件工具必须限定在工作目录内，否则一段提示注入（某个文件里写着"请读取 `~/.ssh/id_rsa`"）就能诱导模型去翻私钥。

校验做了两层：

1. **词法层**：`root.resolve(requested).normalize()` 后检查是否仍以 root 开头 —— 挡住 `../../` 穿越
2. **物理层**：`toRealPath()` 后再查一次 —— 挡住**符号链接绕过**

只做第 1 层是很常见的疏漏：工作目录里放一个指向外部的软链接，词法上看它就在目录内，实际解析后跑到了外面。

### 3.3 工具的失败要返回文本，不能抛异常

文件不存在、路径越界这类情况，应该作为**正常结果文本**返回给模型，让它自己决定下一步（换个路径重试、或告诉用户找不到）。抛异常会让整轮对话中断，agent 就失去了自我纠错的能力。

但"约定"这种东西靠自觉很容易破 —— 我写的时候就违反了（见 4.4）。所以最后用**模板方法**把它固化成结构：

```java
default String execute(String argumentsJson) {
    try {
        return doExecute(argumentsJson);       // 子类只写自己的逻辑
    } catch (WorkspaceViolationException e) {
        return "错误：" + e.getMessage();       // 异常在这里统一转成文本
    }
}
```

将来新增工具时即使忘了处理异常，行为也是对的。

### 3.4 API Key 只从环境变量读

Key 绝不写进任何会被提交的文件。`application.yml` 里是 `api-key: ${DEEPSEEK_API_KEY:}`，同时 `.gitignore` 加了 `application-local.yml` / `.env` / `*.key`。

另外做了**快速失败**：缺 Key 时在启动阶段就报错并打印三种配置方式，而不是等用户提问才报一个含糊的 401。

---

## 四、踩过的坑

### 4.1 XML 注释里不能有 `--`

**现象**：`mvn package` 报 `Non-parseable POM`。

**原因**：我在 pom 注释里写了 `用 --release 21 编译`，而 XML 规范禁止注释中出现连续两个短横线。

**处理**：改写注释措辞。另外这次还发现一个问题 —— 我当时用 `mvn ... | tail -40` 看输出，**管道的退出码掩盖了 Maven 的失败**，导致差点以为构建成功了。之后所有构建命令都加 `set -o pipefail`。

### 4.2 Windows 控制台编码：`System.out` 是 GBK

**现象**：程序输出的中文在终端里是 `����̨���`。

**排查**：

```
file.encoding   = UTF-8    ← Java 18+ 的 JEP 400 默认
native.encoding = GBK      ← Windows 中文区域设置
stdout.encoding = GBK      ← 罪魁祸首
```

**原因**：`System.out` 的输出编码跟随操作系统控制台，而终端（IDEA / Git Bash）按 UTF-8 解码。

**关键点**：`logging.charset.console: UTF-8` **只管日志框架，管不到 `System.in` / `System.out`**。

**处理**：
- 日志输出 → 配 `logging.charset.console: UTF-8`
- 终端对话 → 显式构造 UTF-8 流：`new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8)` 和 `new InputStreamReader(System.in, UTF_8)`
- 原生 Windows cmd 需要 `chcp 65001` 才配套，已写进 README

### 4.3 `System.console()` 在 IDEA 里返回 null

**现象**：本来想用 `System.console() != null` 判断"是不是交互式终端"，决定要不要开终端对话。

**问题**：IDEA 的运行控制台**不是真正的终端**，`System.console()` 在那里返回 null。用它做判断会导致"在 IDEA 里跑不出终端对话"—— 而 IDEA 恰恰是最主要的开发场景。

**处理**：改用配置项 `agent.cli.enabled` 控制（默认开），stdin 关闭时循环自然退出，不影响 Web 服务。

### 4.4 工具直接抛异常，违背了自己定的契约

**现象**：测试里直接调 `listFiles.execute("{\"path\":\"../..\"}")` 时抛出 `WorkspaceViolationException`，而不是返回错误文本。

**原因**：`WorkspaceGuard.resolve()` 会抛异常，而工具没有捕获。生产路径下有 `ToolRegistry` 兜住，所以端到端行为是对的 —— 但**契约不该靠调用方保证**。

**处理**：改用模板方法（见 3.3），把兜底从"约定"变成"结构"。

### 4.5 Windows 8.3 短路径导致沙箱根目录不一致

**现象**：`WorkspaceGuardTest` 里两个用例失败：

```
expected: C:\Users\ADMINI~1\AppData\Local\Temp\junit-168349...
 but was: C:\Users\Administrator\AppData\Local\Temp\junit-168349...
```

**原因**：`@TempDir` 给的是 8.3 短名，而 `toRealPath()` 会展开成长名。我的 `WorkspaceGuard` 里 `root` 存短名、`realRoot` 存长名，两者不一致，导致 `display()` 里的 `relativize()` 因前缀对不上而失败。

**这不是测试环境特有的问题** —— 只要用户目录含短名，真实运行也会踩到。

**处理**：`root` 本身也过一遍 `toRealPath()`，让两个字段始终一致。

### 4.6 `display(root)` 返回空串

**现象**：让 Agent 列工作目录根，工具输出是 `目录  的内容：` —— **目录名是空的**。

**原因**：`root.relativize(root)` 得到空路径。

**影响**：模型看到一个没有名字的目录头，无法判断自己列的是哪里。

**处理**：空串时返回 `"."`。同时补了回归测试。

### 4.7 递归列出的排序把层级打散了

**现象**：`listFiles(recursive=true)` 的输出里 `src\main\...` 和 `src\test\...` 混在一起。

**原因**：我按 `getFileName()`（叶子名）排序，递归模式下路径层级完全被打散。

**处理**：递归时改按**完整相对路径**排序，输出才是自然的树形顺序。单层模式保持"目录在前、按名排序"。

### 4.8 调试日志刷屏

**现象**：`com.agent: debug` 会把完整的请求体（含读进来的全部文件内容）打到终端，把对话输出淹没。

**处理**：默认级别降到 `info`，需要看原始报文时临时加 `--logging.level.com.agent=debug`。工具调用进度（"第 N 轮：模型请求调用 X 个工具"）保留在 info，作为有用的过程反馈。

### 4.9 顺带一提：不要用 `taskkill /IM java.exe`

测试收尾时我用 `taskkill //F //IM java.exe` 批量杀 Java 进程，被安全策略拦下了 —— **理由是对的**：这会连带杀掉用户正在运行的 IDEA（`com.intellij.idea.Main`）和 Maven 服务进程。

正确做法是用 `jps -l` 找到自己启动的那个 PID，再 `taskkill //F //PID <pid>` 精确关闭。

---

## 五、验证记录

### 5.1 构建与测试

```
[INFO] Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| 测试类 | 数量 | 覆盖内容 |
|---|---:|---|
| `AgentTest` | 13 | ReAct 循环、工具结果回传、轮数上限、思维链不外泄、截断处理 |
| `DeepSeekClientTest` | 12 | 线格式（下划线字段名）、工具调用解析、未知字段、错误处理 |
| `FileToolsTest` | 25 | 三个工具的正常路径与各种失败情形、越界拦截 |
| `WorkspaceGuardTest` | 16 | 目录穿越、绝对路径越界、符号链接绕过、短路径 |
| `ChatControllerTest` | 11 | HTTP 状态码、会话上下文、参数校验 |
| `AgentApplicationTests` | 3 | 容器启动、工具自动注册 |

### 5.2 真实 API 端到端验证

**终端模式** —— 问"当前目录下有几个 Java 文件？只回答数字。"，日志显示：

```
第 1 轮：模型请求调用 1 个工具
  → listFiles({"path": ".", "recursive": true})
← 模型数出 35 个 .java 文件，回答「35」
```

**Web API** —— `POST /api/chat` 问"当前目录下有哪些文件？"，返回 `historySize: 5`（system + user + assistant工具调用 + tool结果 + assistant回答），模型准确列出了 `pom.xml`、`mvnw`、`README.md` 等真实文件。

**多轮上下文** —— 复用同一 sessionId 追问"src 目录下有什么？"，模型靠上下文正确理解了"src"，`historySize` 从 5 增长到 9，并准确输出了完整的包结构。

**路径沙箱** —— 要求读取 `../../../../Windows/System32/drivers/etc/hosts`：

```
Agent > 该路径超出了我的工作目录 C:\...\lab1\lab1，出于安全限制，
        我只能访问工作目录以内的文件，因此该请求被拒绝。
```

拦截生效，且模型能据此向用户做出合理解释。

---

## 六、已知限制与后续方向

| 限制 | 说明 | 可行的改进 |
|---|---|---|
| 会话只存内存 | 进程重启即丢失 | `SessionStore` 是唯一要改的地方，可换成文件或数据库 |
| 非流式响应 | 长回答要等全部生成完才显示 | 改用 SSE 流式输出，逐字显示 |
| 无上下文压缩 | 聊得越久 token 线性增长，最终会超出窗口 | 做摘要压缩或滑动窗口 |
| 工具固定为 3 个 | 无法在运行期增减 | `ToolRegistry` 已支持自动发现，加新工具只需新增一个 `@Component` |
| 无鉴权 | Web API 任何人都能调 | 生产部署前必须加认证 |

---

## 七、这次改造的一点体会

**"能跑"和"跑对了"之间隔着一堆不会报错的假设。**

这个项目里最花时间的不是写代码，而是核实假设：DeepSeek 是不是推理模型（影响 `max_tokens` 策略）、Spring Boot 4 的包名变了没有（影响所有模型类）、`ApplicationRunner` 阻塞会不会堵住 Tomcat（影响整体架构）。这些如果猜错，代码照样能编译通过，只是到运行时才以各种诡异的形式出问题。

另一个体会是关于**测试的价值**：这次抓到的 8 个 bug 里，`display()` 返回空串、8.3 短路径不一致、工具抛异常违背契约这三个，都是端到端跑一遍**看不出来或很难定位**的 —— 是测试直接指到了具体位置。这也印证了课程任务书里那句话：测试是验证 AI 生成代码的基础。
