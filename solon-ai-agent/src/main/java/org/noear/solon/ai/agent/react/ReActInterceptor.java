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
package org.noear.solon.ai.agent.react;

import org.noear.solon.flow.intercept.FlowInterceptor;
import org.noear.solon.lang.Preview;

import java.util.Map;

/**
 * ReAct 拦截器
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface ReActInterceptor extends FlowInterceptor {
    /**
     * 思考时触发
     */
    default void onThought(ReActTrace record, String thought) {
    }

    /**
     * 调用工具前触发
     */
    default void onAction(ReActTrace record, String toolName, Map<String, Object> args) {
    }

    /**
     * 工具返回结果后触发
     */
    default void onObservation(ReActTrace record, String result) {
    }


    /**
     * 创建一个默认的拦截器
     */
    static SimpleReActInterceptor.Builder builder() {
        return new SimpleReActInterceptor.Builder();
    }
}