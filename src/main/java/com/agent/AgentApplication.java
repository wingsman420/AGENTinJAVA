package com.agent;

import com.agent.cli.ConsoleEncoding;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 程序主入口。
 *
 * <p>{@code @SpringBootApplication} 只扫描<b>本包及子包</b>，所以所有类都必须放在
 * {@code com.agent} 下面，放到外面 Spring 找不到 —— 这是初学者最常踩的坑。
 *
 * <h2>两种使用方式，同一个 jar</h2>
 * <pre>
 * 本地开发（终端对话 + Web API 同时可用）:
 *   java -jar target/agent-cli-0.1.0.jar
 *
 * 服务器部署（只提供 Web API，不起终端交互）:
 *   java -jar target/agent-cli-0.1.0.jar --agent.cli.enabled=false
 * </pre>
 *
 * <p>关于"终端对话和 Web 服务如何共存"：Tomcat 在 {@code ApplicationRunner}
 * 执行<b>之前</b>就已经启动完毕，请求由 Tomcat 自己的线程处理。所以终端里那个
 * 阻塞的输入循环不会影响 Web 服务 —— 两者互不干扰。
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        // 必须在 Spring 启动之前设置：日志框架的编码只在初始化时读一次，之后再改就来不及了。
        //
        // 为什么需要这一步：application.yml 里把 logging.charset.console 配成了 UTF-8，
        // 这在 IDEA 和 Git Bash 里是对的，但在原生 Windows 控制台（cmd / PowerShell，
        // 中文下用 GBK 代码页）里会让日志中文乱码。这里改成按实际终端探测 ——
        // 挂在真正的系统控制台上就用它的代码页，否则保持 UTF-8。
        if (ConsoleEncoding.hasRealConsole()) {
            System.setProperty("logging.charset.console", ConsoleEncoding.detect().name());
        }
        SpringApplication.run(AgentApplication.class, args);
    }
}
