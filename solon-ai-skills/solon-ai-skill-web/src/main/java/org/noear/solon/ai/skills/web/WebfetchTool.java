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
import org.noear.solon.ai.chat.tool.AbsToolProvider;
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
public class WebfetchTool extends AbsToolProvider {
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int MAX_TIMEOUT_MS = 120000;
    private static final long MAX_RESPONSE_SIZE = 5 * 1024 * 1024; // 5MB 硬限制

    private static final WebfetchTool instance = new WebfetchTool();

    public static WebfetchTool getInstance() {
        return instance;
    }

    @ToolMapping(name = "webfetch", description = "从 URL 获取网页内容。返回格式支持 markdown, text 或 html。")
    public Document webfetch(
            @Param(name = "url", description = "目标网页的完整 URL（必须包含 http:// 或 https://）") String url,
            @Param(name = "format", required = false, defaultValue = "markdown", description = "返回格式：'markdown', 'text', 'html'") String format,
            @Param(name = "timeout", required = false, description = "超时时间（秒），最大 120 秒") Integer timeoutSeconds
    ) throws Exception {

        // 1. URL 校验
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }

        // 2. 超时计算
        int timeout = (timeoutSeconds == null)
                ? DEFAULT_TIMEOUT_MS
                : Math.min(timeoutSeconds * 1000, MAX_TIMEOUT_MS);

        String finalFormat = (format == null) ? "markdown" : format.toLowerCase();

        // 3. 构建请求 (对齐 opencode 的 Headers)
        HttpUtils http = HttpUtils.http(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
                .header("Accept", getAcceptHeader(finalFormat))
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(timeout);

        // 4. 执行请求与 Cloudflare 穿透逻辑
        HttpResponse response = http.exec("GET");
        if (response.code() == 403 && "challenge".equals(response.header("cf-mitigated"))) {
            response = http.header("User-Agent", "opencode").exec("GET");
        }

        if (response.code() >= 400) {
            throw new RuntimeException("Request failed with status code: " + response.code());
        }

        // 5. 5MB 限制校验 (对齐 opencode)
        long contentLength = response.contentLength();
        if (contentLength > MAX_RESPONSE_SIZE) {
            throw new RuntimeException("Response too large (exceeds 5MB limit)");
        }

        byte[] bodyBytes = response.bodyAsBytes();
        if (bodyBytes == null || bodyBytes.length > MAX_RESPONSE_SIZE) {
            throw new RuntimeException("Response too large (exceeds 5MB limit)");
        }

        String contentType = response.header("Content-Type");
        if (contentType == null) contentType = "";
        String mime = contentType.split(";")[0].trim().toLowerCase();
        String title = url + " (" + contentType + ")";

        // 6. 图片处理
        boolean isImage = mime.startsWith("image/") && !mime.contains("svg") && !mime.contains("vnd.fastbidsheet");
        if (isImage) {
            String base64 = Base64.getEncoder().encodeToString(bodyBytes);
            return new Document()
                    .title(title)
                    .content("Image fetched successfully")
                    .metadata("type", "file")
                    .metadata("mime", mime)
                    .metadata("url", "data:" + mime + ";base64," + base64);
        }

        // 7. 内容转换核心逻辑
        String rawContent = new String(bodyBytes, StandardCharsets.UTF_8);
        String output;

        // 仅在 Content-Type 包含 HTML 时进行转换，否则直接输出
        boolean isHtml = contentType.contains("text/html");

        if ("markdown".equals(finalFormat) && isHtml) {
            output = convertHtmlToMarkdown(rawContent);
        } else if ("text".equals(finalFormat) && isHtml) {
            output = extractTextFromHtml(rawContent);
        } else {
            output = rawContent;
        }

        return new Document()
                .title(title)
                .content(output)
                .metadata("url", url)
                .metadata("contentType", contentType);
    }

    private String extractTextFromHtml(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        // 移除不可见内容标签
        doc.select("script, style, noscript, iframe, object, embed").remove();
        return doc.text().trim();
    }

    private String convertHtmlToMarkdown(String html) {
        // 使用 Flexmark 进行转换，配置尽量简约
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