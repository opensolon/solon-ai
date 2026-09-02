/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.llm.dialect.openai;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.generate.GenerateConfig;
import org.noear.solon.ai.generate.GenerateResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI 生成方言（图像/音频生成兼容形态）解析测试
 *
 * <p>对齐协议要点：</p>
 * <ul>
 *   <li>异步端点只回 {@code task_id}，须拼成任务查询地址</li>
 *   <li>同步端点回 {@code data[]}，直接映射为 GenerateContent 列表</li>
 *   <li>两者都缺失时不得臆造数据</li>
 *   <li>{@code usage.total_tokens} 为 optional，缺省用 prompt+completion 兜底</li>
 * </ul>
 */
public class OpenaiGenerateDialectTest {
    private final OpenaiGenerateDialect dialect = OpenaiGenerateDialect.getInstance();

    private GenerateConfig newConfig() {
        GenerateConfig config = new GenerateConfig();
        config.setModel("gpt-image-1");
        config.setTaskUrl("https://api.example.com/v1/tasks/");
        return config;
    }

    @Test
    public void singletonAndCapabilityFlags() {
        assertSame(dialect, OpenaiGenerateDialect.getInstance(), "方言应为单例");
        assertTrue(dialect.isDefault(), "OpenAI 形态是生成的默认方言");
        assertFalse(dialect.matched(newConfig()));
    }

    @Test
    public void asyncTaskId_mappedToTaskUrl() {
        String json = "{\"model\":\"gpt-image-1\",\"task_id\":\"task_9527\"}";

        GenerateResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertNull(resp.getError());
        assertTrue(resp.hasData());
        assertEquals(1, resp.getData().size());
        assertEquals("https://api.example.com/v1/tasks/task_9527", resp.getContent().getUrl());
        // 异步模式只有地址，没有 base64 数据
        assertNull(resp.getContent().getData());
        assertNull(resp.getUsage());
    }

    @Test
    public void syncData_mappedToContentList() {
        String json = "{\"model\":\"gpt-image-1\",\"data\":["
                + "{\"url\":\"https://cdn.example.com/a.png\",\"mimeType\":\"image/png\"},"
                + "{\"data\":\"QUJD\",\"mimeType\":\"image/jpeg\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":13,\"total_tokens\":20}}";

        GenerateResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertTrue(resp.hasData());
        assertEquals(2, resp.getData().size());
        assertEquals("https://cdn.example.com/a.png", resp.getData().get(0).getUrl());
        assertEquals("image/png", resp.getData().get(0).getMimeType());
        assertEquals("QUJD", resp.getData().get(1).getData());
        // getValue(): url 优先，其次 data
        assertEquals("QUJD", resp.getData().get(1).getValue());

        assertNotNull(resp.getUsage());
        assertEquals(7, resp.getUsage().promptTokens());
        assertEquals(13, resp.getUsage().completionTokens());
        assertEquals(20, resp.getUsage().totalTokens());
    }

    @Test
    public void neitherTaskIdNorData_returnsNoData() {
        GenerateResponse resp = dialect.parseResponseJson(newConfig(), "{\"model\":\"gpt-image-1\"}");

        assertNull(resp.getError());
        assertFalse(resp.hasData());
        assertNull(resp.getData());
        assertNull(resp.getContent(), "无数据时不应返回内容");
    }

    @Test
    public void usageWithoutTotalTokens_fallbackToSum() {
        String json = "{\"model\":\"m\",\"data\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":6}}";

        GenerateResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertNotNull(resp.getUsage());
        assertEquals(10, resp.getUsage().totalTokens(), "total_tokens 缺省应等于 prompt+completion");
        assertEquals(0, resp.getUsage().thinkTokens(), "生成接口无思考 token");
    }

    @Test
    public void errorNode_extractedWithCodeAsType() {
        // type 缺失时用 code 补位（官方 error 结构 {message,type,code}）
        String json = "{\"model\":\"m\",\"error\":{\"message\":\"content policy violation\",\"code\":\"content_filter\"}}";

        GenerateResponse resp = dialect.parseResponseJson(newConfig(), json);

        assertNotNull(resp.getError());
        assertEquals("[content_filter] content policy violation", resp.getError().getMessage());
        assertNull(resp.getData());
        assertNull(resp.getUsage());
    }
}
