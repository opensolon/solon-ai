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
package org.noear.solon.ai.agent.util;

import org.noear.solon.core.util.Assert;
import org.noear.solon.expression.snel.SnEL;
import org.noear.solon.flow.FlowContext;

/**
 * 模版解析工具类
 * <p>用于对 Agent 指令或描述中的占位符进行变量动态替换</p>
 *
 * @author noear
 * @since 3.8.1
 */
public class SnelUtil {
    /**
     * 渲染模板（将占位符替换为上下文中的实际值）
     */
    public static String render(String tmpl, FlowContext context) {
        if (Assert.isEmpty(tmpl)) {
            return tmpl;
        }

        // 同时支持 #{} 和 ${} 两种常见的占位符风格
        if (tmpl.contains("#{") || tmpl.contains("${")) {
            if (context != null && context.model() != null) {
                return SnEL.evalTmpl(tmpl, context.model());
            }
        }

        return tmpl;
    }
}