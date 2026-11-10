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
package org.noear.solon.ai.agent;

import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.lang.Preview;

/**
 * 智能体系统提示词
 *
 * <p>语言只由配置决定，模板不得自带语言：渲染时取 {@code trace.getOptions().getLocale()}，
 * 规则为 {@code options.locale}（或所在团队的语言）&gt; {@link AgentI18n#getDefaultLocale()}（默认中文）。
 * 模板只负责“按给定语言渲染文案”，因此同一个模板实例可以在不同请求上输出不同语言。</p>
 *
 * <p>4.2 起删除了 {@code AgentSystemPrompt#getLocale()}：模板自带语言不可配置，它会让
 * {@code options.locale} 之外多出一个语言来源，导致提示词与协议指令中英混排。</p>
 *
 * @author noear
 * @since 3.9.5
 */
@Preview("3.9.5")
@FunctionalInterface
public interface AgentSystemPrompt<Tr> {
    /**
     * 获取系统提示词（支持 SnEL 模板）
     */
    String getSystemPrompt(Tr trace);
}
