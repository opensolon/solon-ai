/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.input;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.lang.Nullable;

import java.util.function.Predicate;

/**
 * 子代理提及解析
 * <p>
 * 三端原本各写一遍：CLI 只剥离、Web 额外用 selectedAgent 兜底、桌面端还多一次 trim
 * 和一次「请求体显式指定 agent」的优先级。这里统一为「识别 + 剥离 + 兜底」，
 * 三端的差异通过 {@link Options} 显式声明，避免再靠各写一份来维持。
 *
 * <p>核心逻辑只依赖一个「名字是否有效」的谓词，不依赖 HarnessEngine，
 * 因此可以脱离引擎单测；{@link #parse(HarnessEngine, String, Options)} 只是便利重载。</p>
 *
 * @author noear
 * @since 4.2
 */
public final class AgentMentions {
    /**
     * 默认子代理提及前缀
     */
    public static final String DEFAULT_PREFIX = "@";

    private AgentMentions() {
    }

    /**
     * 子代理解析结果
     */
    public static final class Mention {
        private final String agentName;
        private final String text;

        Mention(String agentName, String text) {
            this.agentName = agentName;
            this.text = text;
        }

        /**
         * 生效的子代理名（无有效提及时为 null）
         */
        public String getAgentName() {
            return agentName;
        }

        /**
         * 剥离提及后的输入文本
         */
        public String getText() {
            return text;
        }

        public boolean hasAgent() {
            return agentName != null;
        }
    }

    /**
     * 解析策略。三端差异都收在这里，默认值等于 CLI/Web 的既有口径。
     */
    public static final class Options {
        private String prefix = DEFAULT_PREFIX;
        private String explicitAgent;
        private String selectedAgent;
        private Predicate<String> nameValidator;
        private boolean trimContent;
        private boolean agentAware = true;

        /**
         * 子代理提及前缀（默认 "@"，支持多字符）
         */
        public Options prefix(@Nullable String prefix) {
            if (prefix != null && !prefix.isEmpty()) {
                this.prefix = prefix;
            }
            return this;
        }

        /**
         * 请求体 / 调用参数中显式指定的子代理，优先级高于输入里的提及。
         * 调用方应先自行校验其有效性并给出错误提示；此处只负责生效与剥离。
         */
        public Options explicitAgent(@Nullable String explicitAgent) {
            this.explicitAgent = explicitAgent;
            return this;
        }

        /**
         * 选择器传入的子代理（仅当输入未显式提及时作为兜底）
         */
        public Options selectedAgent(@Nullable String selectedAgent) {
            this.selectedAgent = selectedAgent;
            return this;
        }

        /**
         * 名字格式校验（如桌面端的字符集/长度限制）。
         * 未设置时只按子代理表判断是否存在。
         */
        public Options nameValidator(@Nullable Predicate<String> nameValidator) {
            this.nameValidator = nameValidator;
            return this;
        }

        /**
         * 剥离提及后是否去掉剩余首尾空白。
         * 桌面端需要（其输入框允许换行与缩进），CLI/Web 保持既有口径。
         */
        public Options trimContent(boolean trimContent) {
            this.trimContent = trimContent;
            return this;
        }

        /**
         * 是否解析输入开头的子代理提及（默认 true）。
         * 置为 false 时原文原样保留，子代理只取显式指定值与选择器兜底值。
         * 适用于「文本由程序生成」的路径（如 goal objective）：即使以 {@code @xxx} 开头，
         * 也不应被当作子代理提及剥离。
         */
        public Options agentAware(boolean agentAware) {
            this.agentAware = agentAware;
            return this;
        }

        public static Options builder() {
            return new Options();
        }
    }

    /**
     * 解析输入开头的提及（默认前缀，CLI/Web 口径）
     *
     * @param input         原始输入
     * @param selectedAgent 选择器传入的子代理（仅当输入未显式提及时作为兜底）
     */
    public static Mention parse(HarnessEngine engine, String input, @Nullable String selectedAgent) {
        return parse(engine, input, Options.builder().selectedAgent(selectedAgent));
    }

    /**
     * 按策略解析输入开头的提及（前缀取自引擎配置）
     */
    public static Mention parse(HarnessEngine engine, String input, Options options) {
        return parse(engine, null, input, options);
    }

    /**
     * 会话感知版：本会话的会话级子代理（{@code generate(saveToFile=false)}）也能被 {@code @提及}
     *
     * @since 4.2.0
     */
    public static Mention parse(HarnessEngine engine, @Nullable AgentSession session, String input, Options options) {
        if (options == null) {
            options = Options.builder();
        }
        return parse(name -> engine.resolveAgent(session, name) != null, input, options.prefix(engine.getAgentPrefix()));
    }

    /**
     * 按策略解析（核心逻辑，不依赖引擎）
     *
     * @param hasAgent 判断子代理是否存在
     */
    public static Mention parse(Predicate<String> hasAgent, String input, Options options) {
        if (options == null) {
            options = Options.builder();
        }

        final String prefix = options.prefix == null || options.prefix.isEmpty() ? DEFAULT_PREFIX : options.prefix;

        String text = input;
        String agentName = null;

        // 1. 请求体显式指定：优先级最高，只剥离与它同名的那个前缀
        if (options.explicitAgent != null && !options.explicitAgent.isEmpty()) {
            agentName = options.explicitAgent;
            text = stripMention(text, prefix, agentName);
        } else if (options.agentAware && text != null && text.startsWith(prefix)) {
            // 2. 输入开头的提及：必须是有效子代理名，否则整段保留原文交给主 Agent
            int idx = firstWhitespaceIndex(text);
            if (idx > prefix.length()) {
                String mentioned = text.substring(prefix.length(), idx);
                if (isValid(hasAgent, options, mentioned)) {
                    agentName = mentioned;
                    text = text.substring(idx + 1);
                }
            }
        }

        // 3. 选择器兜底：仅当本轮到此刻仍未确定子代理
        if (agentName == null && options.selectedAgent != null && !options.selectedAgent.isEmpty()
                && isValid(hasAgent, options, options.selectedAgent)) {
            agentName = options.selectedAgent;
        }

        if (options.trimContent && text != null) {
            text = text.trim();
        }

        return new Mention(agentName, text);
    }

    private static boolean isValid(Predicate<String> hasAgent, Options options, String name) {
        if (options.nameValidator != null && !options.nameValidator.test(name)) {
            return false;
        }
        return hasAgent.test(name);
    }

    /**
     * 剥离开头的 {@code 前缀 + 名字}：必须存在空白边界，并吃掉其后所有空白。
     * 与桌面端 removeLeadingAgentMention 口径一致；不匹配时原样返回。
     */
    private static String stripMention(String input, String prefix, String agentName) {
        if (input == null) {
            return null;
        }

        String mention = prefix + agentName;
        if (!input.startsWith(mention)) {
            return input;
        }
        if (input.length() > mention.length() && !Character.isWhitespace(input.charAt(mention.length()))) {
            return input;
        }

        int contentStart = mention.length();
        while (contentStart < input.length() && Character.isWhitespace(input.charAt(contentStart))) {
            contentStart++;
        }
        return input.substring(contentStart);
    }

    private static int firstWhitespaceIndex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
