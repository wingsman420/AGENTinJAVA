package com.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作目录白名单的测试。
 *
 * <p>这个白名单是本次改动引入的**新安全边界**：工作目录从"服务端配置"变成了
 * "客户端可指定"，也就意味着登录用户能把路径沙箱的根指向任意位置。
 * 白名单是唯一的约束手段，所以它的判定逻辑必须准确 —— 判错一次就是
 * "本该被拒绝的目录被接受了"。
 */
class AgentPropertiesTest {

    @TempDir
    Path allowedRoot;

    @TempDir
    Path outsideRoot;

    private AgentProperties withAllowedRoots(List<Path> roots) {
        return new AgentProperties(
                "http://localhost:1", "key", "model", 1024,
                Duration.ofSeconds(5), 8,
                allowedRoot, roots, new AgentProperties.Cli(false));
    }

    @Test
    void 没配白名单时一律放行() {
        AgentProperties props = withAllowedRoots(List.of());

        assertThat(props.hasAllowedRoots()).isFalse();
        assertThat(props.isWorkspaceAllowed(outsideRoot)).isTrue();
    }

    @Test
    void 白名单内的路径被允许() {
        AgentProperties props = withAllowedRoots(List.of(allowedRoot));

        assertThat(props.isWorkspaceAllowed(allowedRoot)).isTrue();
    }

    @Test
    void 白名单的子目录也被允许() throws Exception {
        AgentProperties props = withAllowedRoots(List.of(allowedRoot));
        Path sub = java.nio.file.Files.createDirectories(allowedRoot.resolve("proj/sub"));

        // 允许一个根，就应该允许它下面的项目 —— 否则每开一个子目录都要改配置
        assertThat(props.isWorkspaceAllowed(sub)).isTrue();
    }

    @Test
    void 白名单外的路径被拒绝() {
        AgentProperties props = withAllowedRoots(List.of(allowedRoot));

        assertThat(props.isWorkspaceAllowed(outsideRoot)).isFalse();
    }

    @Test
    void 白名单外的子目录同样被拒绝() throws Exception {
        AgentProperties props = withAllowedRoots(List.of(allowedRoot));
        Path outsideSub = java.nio.file.Files.createDirectories(outsideRoot.resolve("proj"));

        // 关键：不能因为"名字像"或"层级相同"就放行，必须是真正的路径包含关系
        assertThat(props.isWorkspaceAllowed(outsideSub)).isFalse();
    }

    @Test
    void 白名单路径会先规范化再比对() {
        // 配置里写成带尾部斜杠或带 .. 的形式，不应该影响判定
        AgentProperties props = withAllowedRoots(List.of(allowedRoot.resolve(".")));

        assertThat(props.isWorkspaceAllowed(allowedRoot)).isTrue();
        assertThat(props.isWorkspaceAllowed(allowedRoot.resolve("sub"))).isTrue();
    }

    @Test
    void 多个白名单根任一命中即放行() {
        AgentProperties props = withAllowedRoots(List.of(outsideRoot, allowedRoot));

        assertThat(props.isWorkspaceAllowed(allowedRoot)).isTrue();
        assertThat(props.isWorkspaceAllowed(outsideRoot)).isTrue();
    }

    @Test
    void 白名单里的null元素被忽略() {
        AgentProperties props = withAllowedRoots(java.util.Arrays.asList(null, allowedRoot));

        assertThat(props.hasAllowedRoots()).isTrue();
        assertThat(props.isWorkspaceAllowed(allowedRoot)).isTrue();
    }
}
