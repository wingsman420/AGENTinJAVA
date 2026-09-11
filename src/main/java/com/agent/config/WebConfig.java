package com.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Web 层配置：给 JSON 响应补上 {@code charset=UTF-8} 声明。
 *
 * <h2>为什么需要这个</h2>
 * 从 Spring 5 开始，JSON 响应的 Content-Type 只写 {@code application/json}，
 * 不带 {@code charset} —— 理由是 JSON 规范（RFC 8259）规定 JSON 文本默认就是 UTF-8，
 * 所以声明是多余的。
 *
 * <p>理论上没错，但**实践中有客户端不认这个默认值**。最典型的就是
 * Windows PowerShell 5.1 的 {@code Invoke-RestMethod}：响应头不带 charset 时，
 * 它按 ISO-8859-1（Latin-1）解码，于是服务端返回的 UTF-8 中文全变成
 * {@code å½åå·¥ä½ç®å½} 这种乱码。
 *
 * <p>而本项目的 Web API 定位就是"给远程客户端调用"，Windows 上 PowerShell 是
 * 最常见的调用方之一。与其让每个调用方各自处理，不如服务端把话说清楚 ——
 * 补上 charset 声明，所有客户端都能正确解码。
 *
 * <h2>实现方式</h2>
 * 把 {@code application/json;charset=UTF-8} 插到每个 JSON 转换器支持列表的**第一位**。
 * 响应头取的是列表里第一个媒体类型，这样它就会带上 charset。
 *
 * <p>不影响请求匹配：{@code MediaType.includes()} 比较时忽略参数，
 * 所以客户端发 {@code Accept: application/json} 依然能匹配上。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final MediaType JSON_UTF8 =
            new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            // setSupportedMediaTypes 定义在 AbstractHttpMessageConverter 上，
            // 不在 HttpMessageConverter 接口里，所以这里要做一次类型判断。
            if (!(converter instanceof AbstractHttpMessageConverter<?> patchable)) {
                continue;
            }
            List<MediaType> supported = converter.getSupportedMediaTypes();
            if (!supported.contains(MediaType.APPLICATION_JSON)) {
                continue;   // 不是 JSON 转换器，不动它
            }

            List<MediaType> patched = new ArrayList<>(supported.size());
            patched.add(JSON_UTF8);
            for (MediaType type : supported) {
                // 去掉原来不带 charset 的那条，避免列表里出现重复的 JSON 项
                if (!type.equals(MediaType.APPLICATION_JSON)) {
                    patched.add(type);
                }
            }
            patchable.setSupportedMediaTypes(patched);
        }
    }
}
