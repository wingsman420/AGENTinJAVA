package com.agent.tool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读文本文件的小工具，给 {@link ReadFileTool} 和 {@link SearchCodeTool} 共用。
 *
 * <p>用**宽松解码**：遇到非法字节替换成 U+FFFD 而不是抛异常。工程目录里
 * 经常混着 UTF-8 和 GBK 编码的文件，严格模式只要碰到一个坏字节就整个读失败，
 * 宽松模式至少能把大部分内容读出来 —— 对 agent 来说，"读到 95% 并告诉模型
 * 可能有乱码"远好于"直接失败"。
 */
final class TextReader {

    private TextReader() {
    }

    /** 判断是否二进制文件：开头 8KB 里出现 NUL 字节就认为是。 */
    private static final int SNIFF_BYTES = 8192;

    static String read(Path path) throws IOException {
        return decode(Files.readAllBytes(path));
    }

    /**
     * 按 UTF-8 宽松解码。
     *
     * <p>{@code CharacterCodingException} 确实可能抛出（即便设置了 REPLACE，
     * 某些极端情况下仍会），所以这里必须处理，不能省。
     */
    static String decode(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            // 去掉 UTF-8 BOM，否则它会被当成正文的第一个字符混进结果
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }
            return text;
        } catch (CharacterCodingException e) {
            throw new IOException("文件解码失败: " + e.getMessage(), e);
        }
    }

    /** 粗略判断是否二进制内容。 */
    static boolean looksBinary(Path path) {
        try (var in = Files.newInputStream(path)) {
            byte[] buf = new byte[SNIFF_BYTES];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true; // 读不了就当二进制跳过，避免在一个坏文件上反复失败
        }
    }
}
