/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.web;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.jsoup.Jsoup;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.annotation.Param;
import org.noear.solon.net.http.HttpResponse;
import org.noear.solon.net.http.HttpUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Web 抓取工具 (类似 curl)
 *
 * @author noear
 * @since 3.9.6
 * */
public class WebfetchTool {
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_RETURN_LIMIT = 128 * 1024; // 128KB（限制返回文档大小）

    private static final int MAX_RETURN_LIMIT = 2 * 1024 * 1024; // 2MB
    private static final long MAX_ALLOWED_SIZE = 10 * 1024 * 1024;     // 10MB（限制网页加载）
    private static final int MAX_TIMEOUT_MS = 120000;


    private static final WebfetchTool instance = new WebfetchTool();

    public static WebfetchTool getInstance() {
        return instance;
    }

    @ToolMapping(name = "webfetch", description = "从 URL 获取内容。当您需要检索和分析web内容时，请使用此工具。")
    public Document webfetch(
            @Param(name = "url", description = "目标网页的完整 URL（必须包含 http:// 或 https://）") String url,
            @Param(name = "format", required = false, defaultValue = "markdown", description = "返回内容的格式选项：'markdown' (默认，适合阅读结构)、'text' (纯文本，适合摘要提取) 或 'html' (原始结构)") String format,
            @Param(name = "timeout", required = false, description = "请求超时时间（秒），最大允许 120 秒") Integer timeoutSeconds,
            @Param(name = "max_length", required = false, description = "期望返回的最大字符数，默认 131072 (128KB)，最大支持 2MB") Integer maxLength
    ) throws Exception {

        // 1. URL 合法性校验 (对齐 TypeScript 版)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }

        // 2. 超时计算 (对齐 TypeScript 的 Math.min 逻辑)
        int timeout = (timeoutSeconds == null)
                ? DEFAULT_TIMEOUT_MS
                : Math.min(timeoutSeconds * 1000, MAX_TIMEOUT_MS);

        String finalFormat = (format == null) ? "markdown" : format.toLowerCase();

        // 3. 构建 Headers (对齐 TypeScript 的 Accept 权重和 UA)
        HttpUtils http = HttpUtils.http(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
                .header("Accept", getAcceptHeader(finalFormat))
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(timeout);

        // 4. 执行请求并处理 Cloudflare 反爬重试 (100% 对齐重试逻辑)
        HttpResponse response = http.exec("GET");
        if (response.code() == 403 && "challenge".equals(response.header("cf-mitigated"))) {
            response = http.header("User-Agent", "opencode").exec("GET");
        }

        if (response.code() >= 400) {
            throw new RuntimeException("Request failed with status code: " + response.code());
        }


        // 5. 严格的内容长度检查 (防止内存溢出)
        long contentLength = response.contentLength();
        if (contentLength > MAX_ALLOWED_SIZE) {
            throw new RuntimeException("Response too large: " + contentLength + " bytes (limit: " + MAX_ALLOWED_SIZE + " bytes)");
        }

        byte[] bodyBytes = response.bodyAsBytes();
        if (bodyBytes.length > MAX_ALLOWED_SIZE) {
            throw new RuntimeException("Response too large: " + bodyBytes.length + " bytes (limit: " + MAX_ALLOWED_SIZE + " bytes)");
        }

        String contentType = response.header("Content-Type");
        if (contentType == null) contentType = "";
        String mime = contentType.split(";")[0].trim().toLowerCase();

        // 6. 图片处理逻辑 (对齐 Base64 附件返回)
        boolean isImage = mime.startsWith("image/") && !mime.contains("svg");
        if (isImage) {
            String base64 = Base64.getEncoder().encodeToString(bodyBytes);
            return new Document()
                    .content("Image fetched successfully")
                    .metadata("type", "file")
                    .metadata("mime", mime)
                    .metadata("url", "data:" + mime + ";base64," + base64);
        }

        // 7. 内容转换核心逻辑
        String content = new String(bodyBytes, StandardCharsets.UTF_8);
        String output;

        if (contentType.contains("html") || contentType.contains("xml")) {
            if ("markdown".equals(finalFormat)) {
                output = convertHtmlToMarkdown(content);
            } else if ("text".equals(finalFormat)) {
                output = extractTextFromHtml(content);
            } else {
                output = content;
            }
        } else {
            output = content;
        }


        if (output == null) {
            output = "";
        }

        int rawLength = output.length();
        int lengthLimit = (maxLength == null)
                ? DEFAULT_RETURN_LIMIT
                : Math.min(maxLength, MAX_RETURN_LIMIT);

        boolean isTruncated = false;
        if (output.length() > lengthLimit) {
            output = output.substring(0, lengthLimit) + "\n\n...(content truncated due to length limit)";
            isTruncated = true;
        }

        return new Document()
                .title(url + " (" + contentType + ")")
                .content(output)
                .metadata("url", url)
                .metadata("contentType", contentType)
                .metadata("originalLength", rawLength)
                .metadata("format", finalFormat)
                .metadata("isTruncated", isTruncated); // 告知 LLM 内容是否完整
    }

    private String extractTextFromHtml(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, iframe, object, embed").remove();
        return doc.text().trim();
    }

    private String convertHtmlToMarkdown(String html) {
        return FlexmarkHtmlConverter.builder().build().convert(html);
    }

    private String getAcceptHeader(String format) {
        if ("markdown".equals(format)) {
            return "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1";
        } else if ("text".equals(format)) {
            return "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1";
        } else if ("html".equals(format)) {
            return "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.7, */*;q=0.1";
        } else {
            return "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
        }
    }
}