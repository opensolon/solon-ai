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
package org.noear.solon.ai.agent.react;

import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.Function;

/**
 * ReAct 系统提示词 (System Prompt) 提供者
 * <p>负责组装 ReAct 核心协议，包括角色设定、执行指令及 Thought/Action/Observation 格式约束</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public interface ReActSystemPrompt {
    /**
     * 获取语言环境 (默认中文)
     */
    Locale getLocale();

    /**
     * 获取原始提示词模板
     */
    String getSystemPrompt(ReActTrace trace);


    // --- Builder Pattern ---

    /**
     * 获取提示词构建器
     */
    static Builder builder() {
        return ReActSystemPromptCn.builder();
    }

    /**
     * 构建器接口
     */
    interface Builder {
        /**
         * 设置静态角色
         */
        Builder role(String role);

        /**
         * 设置静态指令
         */
        default Builder instruction(String instruction) {
            return instruction(trace -> instruction);
        }

        /**
         * 设置动态指令逻辑 (如：接近最大步数时提示收敛)
         */
        Builder instruction(Function<ReActTrace, String> instructionProvider);

        /**
         * 构建实例
         */
        ReActSystemPrompt build();
    }
}