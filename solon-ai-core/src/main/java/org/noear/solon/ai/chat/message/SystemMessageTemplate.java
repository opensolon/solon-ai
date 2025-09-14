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
package org.noear.solon.ai.chat.message;

import org.noear.solon.Utils;
import org.noear.solon.core.util.Assert;
import org.noear.solon.expression.snel.SnEL;
import org.noear.solon.lang.Preview;

import java.util.HashMap;
import java.util.Map;

/**
 * 聊天系统消息模板
 *
 * @author noear
 * @since 3.3
 */
@Preview("3.3")
public class SystemMessageTemplate {
    private final String tmpl;
    private final Map<String, Object> params = new HashMap<>();


    /**
     * @param tmpl '${question} \r\n context: ${context}'
     */
    public SystemMessageTemplate(String tmpl) {
        Assert.notNull(tmpl, "tmpl is null");
        this.tmpl = tmpl;
    }

    /**
     * 配置参数
     */
    public SystemMessageTemplate paramAdd(String name, Object value) {
        params.put(name, value);
        return this;
    }

    /**
     * 配置参数
     */
    public SystemMessageTemplate paramAdd(Map<String, Object> args) {
        if (Utils.isNotEmpty(args)) {
            params.putAll(args);
        }

        return this;
    }

    /**
     * 生成
     */
    public SystemMessage generate() {
        String content = SnEL.evalTmpl(tmpl, params);
        return new SystemMessage(content);
    }
}