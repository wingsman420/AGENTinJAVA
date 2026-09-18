package com.agent.web;

import com.agent.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 全局异常 → HTTP 状态码的映射。
 *
 * <p>集中放在一个地方，而不是散在各个 Controller 里：状态码是对外契约的一部分，
 * 分散定义很容易出现"同一个异常在两个接口返回不同状态码"的不一致。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 登录失败 → 401。
     *
     * <p>用 401 而不是 400：这是鉴权失败，客户端据此知道"该去重新登录"，
     * 而不是"请求写错了"。
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", safeMessage(e, "用户名或密码错误")));
    }

    /**
     * 查不到资源 → 404。
     *
     * <p>会话的越权访问也走这里：查不到和"不属于你"返回**同一个**响应，
     * 因为区分它们等于告诉调用方"这个 id 确实存在，只是不归你"。
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", safeMessage(e, "资源不存在")));
    }

    /** 参数不合法（如用户名重复、message 为空）→ 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", safeMessage(e, "请求参数不合法")));
    }

    /**
     * {@code @Valid} 校验失败 → 400。
     *
     * <p>把每个字段的错误拼起来返回，方便调用方一次看到全部问题，
     * 而不是改一个报一个。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(Map.of("error", detail.isEmpty() ? "请求参数校验失败" : detail));
    }

    /** 请求体格式错误（比如不是合法 JSON）→ 400，而不是让框架返回 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求体格式错误，需要合法 JSON"));
    }

    /**
     * 调用大模型失败 → 502。
     *
     * <p>502 而不是 500：错误来自上游服务，不是本服务内部出错，
     * 调用方看到它就知道"重试可能有用"。
     */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<Map<String, String>> handleLlmFailure(LlmException e) {
        log.warn("调用模型失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "调用模型失败", "detail", safeMessage(e, "")));
    }

    private static String safeMessage(Exception e, String fallback) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? fallback : message;
    }
}
