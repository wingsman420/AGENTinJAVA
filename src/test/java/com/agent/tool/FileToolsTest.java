package com.agent.tool;

import com.agent.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个文件工具的测试。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li>正常功能 —— 能列出文件、读出内容、搜到关键字</li>
 *   <li>**失败时返回错误文本而不是抛异常** —— 这是 agent 能自我纠错的前提。
 *       如果抛异常，整轮对话就断了，模型没机会换个路径重试。</li>
 * </ol>
 */
class FileToolsTest {

    @TempDir
    Path workspace;

    private WorkspaceGuard guard;
    private ListFilesTool listFiles;
    private ReadFileTool readFile;
    private SearchCodeTool searchCode;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        guard = new WorkspaceGuard(TestFixtures.props(workspace));
        listFiles = new ListFilesTool(guard, jsonMapper);
        readFile = new ReadFileTool(guard, jsonMapper);
        searchCode = new SearchCodeTool(guard, jsonMapper);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // ---------- listFiles ----------

    @Test
    void listFiles列出目录内容并区分文件和目录() throws IOException {
        write("README.md", "# demo");
        Files.createDirectories(workspace.resolve("src"));

        String result = listFiles.execute("{\"path\":\".\"}");

        assertThat(result).contains("README.md").contains("[目录] src").contains("[文件] README.md");
    }

    @Test
    void listFiles递归模式列出子目录内容() throws IOException {
        write("src/main/App.java", "class App {}");

        String result = listFiles.execute("{\"path\":\".\",\"recursive\":true}");

        assertThat(result).contains("src");
        // 用系统分隔符断言，避免 Windows/Linux 斜杠差异导致测试假失败
        assertThat(result).contains(Path.of("src", "main", "App.java").toString());
    }

    @Test
    void listFiles在目录不存在时返回错误文本() {
        String result = listFiles.execute("{\"path\":\"不存在的目录\"}");

        assertThat(result).startsWith("错误：").contains("目录不存在");
    }

    @Test
    void listFiles目标是文件时提示改用readFile() throws IOException {
        write("a.txt", "x");

        String result = listFiles.execute("{\"path\":\"a.txt\"}");

        assertThat(result).contains("错误：").contains("readFile");
    }

    @Test
    void listFiles拒绝越界路径() {
        String result = listFiles.execute("{\"path\":\"../..\"}");

        assertThat(result).contains("错误：").contains("超出了 agent 的工作目录");
    }

    @Test
    void listFiles空目录给出明确提示() {
        String result = listFiles.execute("{\"path\":\".\"}");

        assertThat(result).contains("是空的");
    }

    @Test
    void listFiles列出工作目录根时标题不是空白() throws IOException {
        write("a.txt", "x");   // 非空目录才会走到带标题的分支

        String result = listFiles.execute("{\"path\":\".\"}");

        // 曾经这里是"目录  的内容："，因为根目录 relativize 自己得到空串
        assertThat(result).contains("目录 . 的内容");
    }

    @Test
    void listFiles空目录的提示里也带上目录名() {
        String result = listFiles.execute("{\"path\":\".\"}");

        assertThat(result).contains("目录 . 是空的");
    }

    @Test
    void listFiles递归结果按完整路径排序而不是按文件名() throws IOException {
        write("src/main/A.java", "class A {}");
        write("src/test/A.java", "class A {}");
        write("docs/readme.md", "x");

        String result = listFiles.execute("{\"path\":\".\",\"recursive\":true}");

        // src 下的内容必须聚在一起，而不是被 docs 或 test 插在中间
        int docsAt = result.indexOf("docs");
        int srcMainAt = result.indexOf(Path.of("src", "main", "A.java").toString());
        int srcTestAt = result.indexOf(Path.of("src", "test", "A.java").toString());
        assertThat(docsAt).isGreaterThanOrEqualTo(0);
        assertThat(srcMainAt).isGreaterThan(docsAt);
        assertThat(srcTestAt).isGreaterThan(srcMainAt);
    }

    @Test
    void listFiles跳过target等构建目录() throws IOException {
        write("target/classes/Big.class", "binary");
        write("src/Real.java", "class Real {}");

        String result = listFiles.execute("{\"path\":\".\",\"recursive\":true}");

        assertThat(result).contains("Real.java").doesNotContain("Big.class");
    }

    // ---------- readFile ----------

    @Test
    void readFile返回带行号的内容() throws IOException {
        write("Demo.java", "第一行\n第二行\n第三行");

        String result = readFile.execute("{\"path\":\"Demo.java\"}");

        assertThat(result).contains("1 | 第一行").contains("2 | 第二行").contains("3 | 第三行");
        assertThat(result).contains("3 行");
    }

    @Test
    void readFile在文件不存在时返回错误文本() {
        String result = readFile.execute("{\"path\":\"没有这个文件.txt\"}");

        assertThat(result).contains("错误：").contains("文件不存在").contains("listFiles");
    }

    @Test
    void readFile缺少path参数时给出可操作的提示() {
        String result = readFile.execute("{}");

        assertThat(result).contains("错误：").contains("path");
    }

    @Test
    void readFile目标为目录时提示改用listFiles() throws IOException {
        Files.createDirectories(workspace.resolve("src"));

        String result = readFile.execute("{\"path\":\"src\"}");

        assertThat(result).contains("错误：").contains("listFiles");
    }

    @Test
    void readFile拒绝越界路径() {
        String result = readFile.execute("{\"path\":\"../../../etc/passwd\"}");

        assertThat(result).contains("错误：").contains("超出了 agent 的工作目录");
    }

    @Test
    void readFile识别二进制文件() throws IOException {
        Path bin = workspace.resolve("image.png");
        Files.write(bin, new byte[]{1, 2, 0, 3, 4});

        String result = readFile.execute("{\"path\":\"image.png\"}");

        assertThat(result).contains("错误：").contains("二进制");
    }

    @Test
    void readFile对格式错误的参数不崩溃() {
        String result = readFile.execute("这不是合法 JSON");

        // 参数解析失败应当降级成"缺参数"提示，而不是抛异常
        assertThat(result).contains("错误：").contains("path");
    }

    // ---------- searchCode ----------

    @Test
    void searchCode找到关键字并带行号() throws IOException {
        write("A.java", "class A {\n  void hello() {}\n}");
        write("B.java", "class B {}");

        String result = searchCode.execute("{\"path\":\".\",\"keyword\":\"hello\"}");

        assertThat(result).contains("A.java:2").contains("void hello()");
        assertThat(result).contains("找到 1 处");
    }

    @Test
    void searchCode无匹配时明确说明() throws IOException {
        write("A.java", "class A {}");

        String result = searchCode.execute("{\"path\":\".\",\"keyword\":\"zzzz不存在\"}");

        assertThat(result).contains("没有找到").contains("zzzz不存在");
    }

    @Test
    void searchCode缺少必填参数时给出提示() {
        String result = searchCode.execute("{\"path\":\".\"}");

        assertThat(result).contains("错误：").contains("keyword");
    }

    @Test
    void searchCode跳过构建目录() throws IOException {
        write("target/generated/Gen.java", "class Gen { String marker = \"NEEDLE\"; }");
        write("src/Real.java", "class Real {}");

        String result = searchCode.execute("{\"path\":\".\",\"keyword\":\"NEEDLE\"}");

        assertThat(result).contains("没有找到");
    }

    @Test
    void searchCode跳过二进制文件() throws IOException {
        Path bin = workspace.resolve("blob.bin");
        Files.write(bin, new byte[]{'N', 'E', 'E', 'D', 'L', 'E', 0, 1});

        String result = searchCode.execute("{\"path\":\".\",\"keyword\":\"NEEDLE\"}");

        assertThat(result).contains("没有找到");
    }

    @Test
    void searchCode拒绝越界路径() {
        String result = searchCode.execute("{\"path\":\"..\",\"keyword\":\"x\"}");

        assertThat(result).contains("错误：").contains("超出了 agent 的工作目录");
    }

    @Test
    void searchCode统计扫描的文件数() throws IOException {
        write("A.java", "class A {}");
        write("B.java", "class B {}");

        String result = searchCode.execute("{\"path\":\".\",\"keyword\":\"class\"}");

        assertThat(result).contains("扫描 2 个文件");
    }

    // ---------- 工具定义 ----------

    @Test
    void 三个工具的定义都符合协议格式() {
        for (AgentTool tool : new AgentTool[]{listFiles, readFile, searchCode}) {
            assertThat(tool.definition().type()).isEqualTo("function");
            assertThat(tool.definition().function().name()).isNotBlank();
            assertThat(tool.definition().function().description()).isNotBlank();
            assertThat(tool.definition().function().parameters().type()).isEqualTo("object");
            assertThat(tool.definition().function().parameters().required()).contains("path");
            assertThat(tool.name()).isEqualTo(tool.definition().function().name());
        }
    }
}
