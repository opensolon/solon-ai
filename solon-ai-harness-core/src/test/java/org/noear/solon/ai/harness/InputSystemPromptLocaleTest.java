/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSystemPrompt;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.input.HarnessInput;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 宿主输入的 systemPrompt / locale：复制不丢、语言环境口径正确
 *
 * <p>这两项都是「本轮」语义，但会被复制（宿主骨架 {@code copyFor}）与重放（命令触发的嵌套输入、
 * 积压续发）。复制路径漏拷会变成静默失效——不报错，只是提示词和语言环境悄悄回到默认。</p>
 */
class InputSystemPromptLocaleTest {
    @Test
    void copyForKeepsSystemPromptAndLocale() {
        AgentSession session = InMemoryAgentSession.of("s1");

        HarnessInput input = HarnessInput.of("hi")
                .systemPrompt("You are a shell assistant.")
                .locale(Locale.ENGLISH);

        HarnessInput copy = input.copyFor(session);

        assertEquals("You are a shell assistant.", copy.getSystemPrompt(), "copyFor 必须原样带上 systemPrompt");
        assertEquals(Locale.ENGLISH, copy.getLocale(), "copyFor 必须原样带上 locale");
        // 原对象不被改写（copyFor 的契约）
        assertNull(input.getSession());
        assertEquals(session, copy.getSession());
    }

    @Test
    void defaultsAreNull() {
        HarnessInput input = HarnessInput.of("hi");

        assertNull(input.getSystemPrompt(), "未设置时沿用智能体模板");
        assertNull(input.getLocale(), "未设置时沿用智能体语言环境");
    }

    @Test
    void localeIsClearedWhenSetNull() {
        HarnessInput input = HarnessInput.of("hi").systemPrompt("x").locale(Locale.ENGLISH);
        input.systemPrompt(null).locale(null);

        assertNull(input.getSystemPrompt());
        assertNull(input.getLocale());
    }

    @Test
    void inputSystemPromptCarriesLocale() {
        AgentSystemPrompt<ReActTrace> prompt = InputPipelineAccess.systemPromptOf("You are a shell assistant.", Locale.ENGLISH);

        assertEquals(Locale.ENGLISH, prompt.getLocale(), "英文提示词必须带上英文语言环境，否则协议指令按中文注入");
        assertEquals("You are a shell assistant.", prompt.getSystemPrompt(null));
    }

    @Test
    void inputSystemPromptFallsBackToFrameworkDefault() {
        AgentSystemPrompt<ReActTrace> prompt = InputPipelineAccess.systemPromptOf("随便一段提示词", null);

        assertEquals(Locale.CHINESE, prompt.getLocale(), "未指定 locale 时沿用框架默认（中文）");
        assertEquals("随便一段提示词", prompt.getSystemPrompt(null));
    }

    /** 包内改私：{@code InputPipeline#inputSystemPromptOf} 是包内可见的 */
    private static class InputPipelineAccess {
        static AgentSystemPrompt<ReActTrace> systemPromptOf(String prompt, Locale locale) {
            return InputPipeline.inputSystemPromptOf(prompt, locale);
        }
    }
}
