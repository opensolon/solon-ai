/*
 * Copyright 2017-2026 noear.org and authors
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
package features.ai.harness.command;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.command.builtin.ModelCommand;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class ModelCommandTest {
    private static ChatConfig model(String name, String description, boolean enabled) {
        ChatConfig c = new ChatConfig();
        c.setName(name);
        c.setModel(name);
        c.setDescription(description);
        c.setEnabled(enabled);
        return c;
    }

    @Test
    void listShowsOnlyEnabledModelsWithoutDescription() {
        List<String> lines = ModelCommand.formatModels(Arrays.asList(
                model("gpt-4o", "GPT-4o 多模态", true),
                model("glm-4", null, true),
                model("disabled-one", "被禁用的模型", false)
        ), "glm-4", Locale.ENGLISH);

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("Models:"));
        // 启用模型只输出 nameOrModel，不拼 description
        assertTrue(lines.get(1).contains("gpt-4o"));
        assertFalse(lines.get(1).contains("GPT-4o"));
        // 当前模型带 active 标记
        assertTrue(lines.get(2).contains("glm-4"));
        assertTrue(lines.get(2).contains("(active)"));
        // 禁用模型被过滤
        assertFalse(String.join("\n", lines).contains("disabled-one"));
    }

    @Test
    void listFollowsTheLocale() {
        List<String> lines = ModelCommand.formatModels(Arrays.asList(model("glm-4", null, true)), "glm-4", null);
        assertTrue(lines.get(0).contains("模型"), "未指定语言：默认语言（中文）");
        assertTrue(lines.get(1).contains("（当前）"));
    }

    @Test
    void listSkipsNullEntriesSafely() {
        List<String> lines = ModelCommand.formatModels(Arrays.asList(
                null,
                model("only-one", null, true)
        ), null, Locale.ENGLISH);

        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("only-one"));
    }
}
