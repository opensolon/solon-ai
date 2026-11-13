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
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.lang.Internal;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置子代理的语言版本（整份提示词按语言分文件）
 *
 * <p>基础文件 {@code META-INF/solon/ai/harness/agents/<name>.md} 是中文版，也是兜底；其它语言放在
 * {@code META-INF/solon/ai/harness/agents-i18n/<name>_<lang>.md}（或 {@code <name>_<lang>_<COUNTRY>.md}）。
 * 语言版本不放在内置挂载的扫描目录里，避免被当成同名代理重复注册。</p>
 *
 * <p>只替换描述与正文（系统提示词）；工具、权限等其余元数据始终以基础文件为准，各语言行为一致。
 * 只作用于内置挂载提供的代理：工作区 / 用户挂载的同名代理原样使用。</p>
 *
 * <p>不产出“某语言的定义副本”：正文在每轮推理时按本轮语言取（见 {@code AgentFactory}），
 * 同一个代理实例在不同语言的轮次上各自正确；描述只用于展示（子代理清单、补全、设置页）。</p>
 *
 * @author noear
 * @since 4.2
 */
@Internal
public final class BuiltinAgentI18n {
    private static final Logger LOG = LoggerFactory.getLogger(BuiltinAgentI18n.class);

    /**
     * 内置子代理所在挂载的别名
     */
    public static final String MOUNT_ALIAS = "@harness-agents";

    /**
     * 语言版本所在的类路径目录
     */
    public static final String I18N_PATH = "META-INF/solon/ai/harness/agents-i18n/";

    /**
     * 名字 + 语言 → 语言版本（空表示没有该语言的文件）；类路径资源不变，缓存不需失效
     */
    private static final Map<String, Optional<Markdown>> CACHE = new ConcurrentHashMap<>();

    private BuiltinAgentI18n() {
    }

    /**
     * 是否为内置挂载提供的定义（只有它们有语言版本）
     */
    public static boolean isBuiltin(@Nullable AgentDefinition definition) {
        if (definition == null || definition.getMountAlias() == null) {
            return false;
        }
        return strip(definition.getMountAlias()).equals(strip(MOUNT_ALIAS));
    }

    /**
     * 指定语言的描述（没有对应语言文件、或不是内置代理时为定义自己的描述）
     *
     * @param definition 定义（null 返回 null）
     * @param locale     语言（null 为默认语言）
     */
    public static @Nullable String descriptionOf(@Nullable AgentDefinition definition, @Nullable Locale locale) {
        if (definition == null) {
            return null;
        }

        Markdown variant = variantOf(definition, locale);
        return variant == null || variant.getDescription() == null ? definition.getDescription() : variant.getDescription();
    }

    /**
     * 指定语言的正文（系统提示词；没有对应语言文件、或不是内置代理时为定义自己的正文）
     *
     * @param definition 定义（null 返回 null）
     * @param locale     语言（null 为默认语言）
     */
    public static @Nullable String systemPromptOf(@Nullable AgentDefinition definition, @Nullable Locale locale) {
        if (definition == null) {
            return null;
        }

        Markdown variant = variantOf(definition, locale);
        return variant == null ? definition.getSystemPrompt() : variant.getContent();
    }

    /**
     * 内置定义在该语言下的语言版本（中文、非内置、或没有语言文件时为 null，即用基础文件）
     */
    private static @Nullable Markdown variantOf(AgentDefinition definition, @Nullable Locale locale) {
        if (isBuiltin(definition) == false) {
            return null;
        }

        Locale resolved = AiI18n.resolve(locale);
        if (AiI18n.isChinese(resolved)) {
            return null;
        }

        return variantOf(definition.getName(), resolved);
    }

    private static String strip(String alias) {
        return alias.startsWith("@") ? alias.substring(1) : alias;
    }

    /**
     * 先找「语言_国家」，再找「语言」，最后用英文（与文案资源一致：非中文的未知语言落回英文）
     */
    private static @Nullable Markdown variantOf(String name, Locale locale) {
        if (name == null) {
            return null;
        }

        if (locale.getCountry().isEmpty() == false) {
            Markdown md = load(name + "_" + locale.getLanguage() + "_" + locale.getCountry());
            if (md != null) {
                return md;
            }
        }

        Markdown md = load(name + "_" + locale.getLanguage());
        if (md != null) {
            return md;
        }

        return load(name + "_en");
    }

    private static @Nullable Markdown load(String baseName) {
        return CACHE.computeIfAbsent(baseName, k -> Optional.ofNullable(read(I18N_PATH + k + ".md"))).orElse(null);
    }

    private static @Nullable Markdown read(String resource) {
        ClassLoader classLoader = BuiltinAgentI18n.class.getClassLoader();
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }

            //与内置挂载的解析口径一致（AgentDescriptor：按行切分后解析）
            String str = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return MarkdownUtil.resolve(Arrays.asList(str.split("\\R", -1)));
        } catch (IOException e) {
            LOG.warn("Builtin agent i18n load failed: {}", resource, e);
            return null;
        }
    }
}
