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
package org.noear.solon.ai.harness.extension.loop;

import org.noear.solon.ai.harness.extension.loop.i18n.LoopI18n;
import org.noear.solon.ai.harness.extension.loop.i18n.LoopI18nKeys;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 定时任务规格不合法（携带文案 key 与变量，由出口按语言渲染）
 *
 * <p>{@link #getMessage()} 按默认语言渲染（与改造前的异常说明一致）；
 * 工具与命令用 {@link #getMessage(Locale)} 按请求语言输出。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public class LoopArgumentException extends IllegalArgumentException {
    private final String key;
    private final Map<String, Object> vars;

    /**
     * @param key        文案 key（{@link LoopI18nKeys} 常量）
     * @param nameValues 变量（名字、值交替）
     */
    LoopArgumentException(String key, Object... nameValues) {
        super(key);
        this.key = key;

        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            map.put((String) nameValues[i], nameValues[i + 1]);
        }
        this.vars = Collections.unmodifiableMap(map);
    }

    /**
     * 文案 key
     */
    public String getKey() {
        return key;
    }

    /**
     * 文案变量（只读）
     */
    public Map<String, Object> getVars() {
        return vars;
    }

    @Override
    public String getMessage() {
        return getMessage(null);
    }

    /**
     * 按语言渲染的说明
     *
     * @param locale 语言（null 为默认语言）
     */
    public String getMessage(@Nullable Locale locale) {
        //key 来自构造参数（调用处为常量），故不经门面的 fmt（资源检查只认常量 key）
        return LoopI18n.messages().fmt(locale, key, vars);
    }

    /**
     * 异常说明：本类按语言渲染，其它异常用原说明
     */
    static String messageOf(Throwable e, @Nullable Locale locale) {
        if (e instanceof LoopArgumentException) {
            return ((LoopArgumentException) e).getMessage(locale);
        } else {
            return e.getMessage();
        }
    }
}
