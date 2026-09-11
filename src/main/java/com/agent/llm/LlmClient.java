package com.agent.llm;

import com.agent.llm.model.ChatRequest;
import com.agent.llm.model.ChatResponse;

/**
 * 大模型客户端的抽象。
 *
 * <p>为什么要抽这一层接口，而不是让 {@code Agent} 直接依赖 {@code DeepSeekClient}？
 *
 * <p>因为 {@code Agent} 里装的是整个项目最核心的逻辑 —— ReAct 循环：什么时候调工具、
 * 工具结果怎么回传、什么时候收尾。这段逻辑必须有测试覆盖。如果它直接依赖具体的
 * HTTP 客户端，那每次跑测试都要真的联网调一次 API：慢、要花钱、网络一抖测试就红、
 * 还没法构造"模型连续调三轮工具"这类边界场景。
 *
 * <p>抽成接口后，测试里换成 {@code FakeLlmClient} 按脚本返回预设响应，
 * 核心逻辑就能被快速、免费、确定性地验证。这就是"依赖抽象而非实现"的实际价值。
 *
 * <p>换供应商（通义、智谱、本地 Ollama 等）时也只需新增一个实现类，
 * {@code Agent} 一行都不用改。
 */
public interface LlmClient {

    /**
     * 发一次对话请求，同步等待完整响应（非流式）。
     *
     * @throws LlmException 网络错误、鉴权失败、响应无法解析等服务端/传输层问题
     */
    ChatResponse chat(ChatRequest request);
}
