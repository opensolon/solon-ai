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
import org.noear.solon.ai.AiMedia;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.TmplUtil;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * 聊天用户消息模板
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class UserMessageTemplate {
    private final String tmpl;
    private final Map<String, Object> params = new HashMap<>();
    private List<AiMedia> medias;


    /**
     * @param tmpl '${question} \r\n context: ${context}'
     */
    public UserMessageTemplate(String tmpl) {
        Assert.notNull(tmpl, "tmpl is null");
        this.tmpl = tmpl;
    }

    /**
     * 配置参数
     *
     * @deprecated 3.3 {@link #paramAdd(String, Object)}
     */
    @Deprecated
    public UserMessageTemplate param(String name, Object value) {
        return paramAdd(name, value);
    }

    /**
     * 配置参数
     */
    public UserMessageTemplate paramAdd(String name, Object value) {
        params.put(name, value);
        return this;
    }

    /**
     * 配置参数
     */
    public UserMessageTemplate paramAdd(Map<String, Object> args) {
        if (Utils.isNotEmpty(args)) {
            params.putAll(args);
        }

        return this;
    }

    /**
     * 配置感知媒体
     */
    public UserMessageTemplate mediaAdd(AiMedia media) {
        if (medias == null) {
            medias = new ArrayList<>();
        }
        medias.add(media);
        return this;
    }

    /**
     * 生成
     */
    public UserMessage generate() {
        String content = TmplUtil.parse(tmpl, params);
        return new UserMessage(content, medias);
    }
}