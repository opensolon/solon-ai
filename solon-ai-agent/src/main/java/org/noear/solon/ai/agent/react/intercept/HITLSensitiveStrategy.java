/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.util.Map;

/**
 * 敏感操作介入策略
 * <p>用于对高危工具进行统一的拦截逻辑配置</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITLSensitiveStrategy implements HITLInterceptor.InterventionStrategy {
    private String comment;

    /** 设置拦截理由文案 */
    public HITLSensitiveStrategy comment(String comment) {
        this.comment = comment;
        return this;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        if (Assert.isEmpty(comment)) {
            return "敏感操作，需要人工介入确认";
        } else {
            return comment;
        }
    }
}