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
 * @author noear
 * @since 4.2
 */
public final class BuiltinAgentI18n {
    private static final Logger LOG = LoggerFactory.getLogger(BuiltinAgentI18n.class);

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
     * 取指定语言的定义（没有对应语言文件、或不是内置代理时原样返回）
     *
     * @param definition        定义（null 原样返回）
     * @param locale            语言（null 为默认语言）
     * @param builtinMountAlias 内置挂载别名（带不带 {@code @} 均可）
     */
    public static AgentDefinition localize(@Nullable AgentDefinition definition, @Nullable Locale locale,
                                           String builtinMountAlias) {
        if (definition == null || isSameAlias(definition.getMountAlias(), builtinMountAlias) == false) {
            return definition;
        }

        Locale resolved = AiI18n.resolve(locale);
        if (AiI18n.isChinese(resolved)) {
            return definition;
        }

        Markdown variant = variantOf(definition.getName(), resolved);
        if (variant == null) {
            return definition;
        }

        AgentDefinition copy = definition.copy();
        copy.setMountAlias(definition.getMountAlias());
        if (variant.getDescription() != null) {
            copy.getMetadata().setDescription(variant.getDescription());
        }
        copy.setSystemPrompt(variant.getContent());
        return copy;
    }

    private static boolean isSameAlias(@Nullable String alias, String builtin) {
        if (alias == null) {
            return false;
        }
        return strip(alias).equals(strip(builtin));
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
