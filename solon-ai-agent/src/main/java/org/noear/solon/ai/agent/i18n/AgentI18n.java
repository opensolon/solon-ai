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
package org.noear.solon.ai.agent.i18n;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.I18nMessages;
import org.noear.solon.i18n.I18nBundleFactory;
import org.noear.solon.lang.Preview;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;

/**
 * 智能体国际化工具（基于 solon-ai-core 的 {@link AiI18n} / {@link I18nMessages}）
 *
 * <p>文案资源按领域拆分为多个内容包（{@link #BUNDLES}，yml 格式），每个包的默认文件为英文（兜底），
 * 中文放在 {@code _zh} 文件中。用户可在自己的类路径下放置 {@code _ja} 等文件扩展语言，
 * 或放置 {@code _zh_CN} 等文件覆盖个别 key。</p>
 *
 * <p>locale 决定规则（只有一个来源：配置）：{@code options.locale}（显式设置） &gt;
 * 所在团队的语言（作为成员时继承） &gt; {@link #getDefaultLocale()}。
 * 提示词模板不自带语言，只按给定语言渲染。</p>
 *
 * <p>默认语言、覆盖工厂与已知语言是全局设置（{@link AiI18n}），与 harness、talent 共享；
 * 本类的同名方法只是转发，便于智能体代码统一从一处调用。</p>
 *
 * <p>参与逻辑判断的标记与关键词通过 {@link #textsOfAllLocales(String)} / {@link #itemsOfAllLocales(String)}
 * 在所有已知语言中合并识别，新增语言只需加资源文件。</p>
 *
 * <p>注意：</p>
 * <ul>
 *     <li>占位符只用命名形式 {@code {name}}（{@link #format(String, Map)}），不走 MessageFormat 或表达式引擎（文案中含 JSON 示例与引号）；</li>
 *     <li>值只写正文，首尾不带换行或空格；段落之间的换行、空格由调用处的代码负责（扩展语言时无需关心空白）；</li>
 *     <li>资源文本禁止出现 "$&#123;" 与 "#&#123;"，以免被提示词的 SnEL 模板渲染误解析；</li>
 *     <li>恢复持久化会话时，智能体会自动登记快照携带的语言（{@link #registerLocaleOf(Prompt)}）。</li>
 * </ul>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class AgentI18n {
    /**
     * 内置内容包（按领域拆分）
     */
    public static final List<String> BUNDLES = MessagesHolder.MESSAGES.bundles();

    /**
     * 请求语言在 Prompt.attrs 与工具上下文中的键（同 {@link AiI18n#ATTR_LOCALE}）
     */
    public static final String ATTR_LOCALE = AiI18n.ATTR_LOCALE;

    private AgentI18n() {
    }

    /**
     * 智能体的文案实例
     */
    public static I18nMessages messages() {
        return MessagesHolder.MESSAGES;
    }

    /**
     * 获取默认语言（未显式设置 locale 时使用；默认中文）
     */
    public static Locale getDefaultLocale() {
        return AiI18n.getDefaultLocale();
    }

    /**
     * 设置默认语言（全局）
     */
    public static void setDefaultLocale(Locale locale) {
        AiI18n.setDefaultLocale(locale);
    }

    /**
     * 设置覆盖用的内容包工厂（全局），支持分布式 i18n
     *
     * <p>内置资源文件始终作为底层；工厂返回的文案只覆盖同名 key，缺失的 key 自动落回内置文案。
     * 传入 null 表示取消覆盖。</p>
     */
    public static void setBundleFactory(I18nBundleFactory factory) {
        AiI18n.setBundleFactory(factory);
    }

    /**
     * 清空缓存（全局；例如热更新资源后）
     */
    public static void clearCache() {
        AiI18n.clearCache();
    }

    /**
     * 归一化 locale（null 时使用默认语言）
     */
    public static Locale resolve(Locale locale) {
        return AiI18n.resolve(locale);
    }

    /**
     * 将请求语言写入 Prompt.attrs（存为语言标签字符串，保证会话快照可序列化与还原）
     */
    public static void putLocale(Prompt prompt, Locale locale) {
        AiI18n.putLocale(prompt, locale);
    }

    /**
     * 从 Prompt.attrs 取请求语言（由智能体在激活 Talent 前写入；取不到时返回 null）
     */
    public static Locale localeOf(Prompt prompt) {
        return AiI18n.localeOf(prompt);
    }

    /**
     * 是否为中文
     */
    public static boolean isChinese(Locale locale) {
        return AiI18n.isChinese(locale);
    }

    /**
     * 是否存在 key
     */
    public static boolean has(Locale locale, String key) {
        return MessagesHolder.MESSAGES.has(locale, key);
    }

    /**
     * 获取文案
     *
     * @throws MissingResourceException 找不到 key 时
     */
    public static String get(Locale locale, String key) {
        return MessagesHolder.MESSAGES.get(locale, key);
    }

    /**
     * 获取文案并替换一个命名占位符（如 {@code {role}}）
     */
    public static String fmt(Locale locale, String key, String name, Object value) {
        return MessagesHolder.MESSAGES.fmt(locale, key, name, value);
    }

    /**
     * 获取文案并替换两个命名占位符
     *
     * @throws IllegalArgumentException 变量名重复时（否则后者会静默覆盖前者，提示词里残留未替换的占位符）
     */
    public static String fmt(Locale locale, String key, String name1, Object value1, String name2, Object value2) {
        return MessagesHolder.MESSAGES.fmt(locale, key, name1, value1, name2, value2);
    }

    /**
     * 获取文案并替换三个命名占位符
     *
     * @throws IllegalArgumentException 变量名重复时
     */
    public static String fmt(Locale locale, String key, String name1, Object value1, String name2, Object value2,
                             String name3, Object value3) {
        return MessagesHolder.MESSAGES.fmt(locale, key, name1, value1, name2, value2, name3, value3);
    }

    /**
     * 获取文案并替换命名占位符
     */
    public static String fmt(Locale locale, String key, Map<String, ?> vars) {
        return MessagesHolder.MESSAGES.fmt(locale, key, vars);
    }

    /**
     * 替换命名占位符 {@code {name}}（见 {@link AiI18n#format(String, Map)}）
     */
    public static String format(String tml, Map<String, ?> vars) {
        return AiI18n.format(tml, vars);
    }

    /**
     * 获取某语言下合并后的全部文案（只读）
     */
    public static Map<String, String> messages(Locale locale) {
        return MessagesHolder.MESSAGES.messages(locale);
    }

    /**
     * 从 Prompt 携带的语言标签登记语言（会话恢复时使用）
     */
    public static void registerLocaleOf(Prompt prompt) {
        AiI18n.registerLocaleOf(prompt);
    }

    /**
     * 登记语言（会话可能先后出现不同语言，参与逻辑判断的标记与关键词会覆盖所有已知语言）
     */
    public static void registerLocale(Locale locale) {
        AiI18n.registerLocale(locale);
    }

    /**
     * 已知语言（内置 + 已使用 + 已登记 + 默认语言）
     */
    public static Set<Locale> knownLocales() {
        return AiI18n.knownLocales();
    }

    /**
     * 取某 key 在所有已知语言下的文案（用于识别标记）
     */
    public static Set<String> textsOfAllLocales(String key) {
        return MessagesHolder.MESSAGES.textsOfAllLocales(key);
    }

    /**
     * 取某 key 在所有已知语言下的列表项（值以 "|" 分隔，去空白、去重；用于关键词识别）
     */
    public static Set<String> itemsOfAllLocales(String key) {
        return MessagesHolder.MESSAGES.itemsOfAllLocales(key);
    }

    /**
     * 延迟初始化（BUNDLES 依赖它，放在独立的持有类中，避免静态字段的初始化顺序问题）
     */
    private static final class MessagesHolder {
        static final I18nMessages MESSAGES = new I18nMessages(
                "solon-ai-agent.i18n.react",
                "solon-ai-agent.i18n.team",
                "solon-ai-agent.i18n.team-protocol",
                "solon-ai-agent.i18n.compress");
    }
}
