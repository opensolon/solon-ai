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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.lang.Preview;

import java.util.Map;

/**
 * 介入判定策略接口
 *
 * @author noear
 * @since 4.0.4
 */
@Preview("4.0.4")
@FunctionalInterface
public interface HITLStrategy {
    /**
     * 评估是否需要干预
     *
     * @return 拦截原因（触发拦截）；null（不拦截，直接执行）
     */
    String evaluate(ReActTrace trace, Map<String, Object> args);
}
