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

import org.noear.solon.lang.Preview;

/**
 * ReAct 执行风格
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public enum ReActStyle {
    /**
     * 结构化文本模式 (论文模式)
     * 表现为：LLM 显式输出 Thought: / Action: {JSON} 等文本标签。
     * 场景：适合推理要求严苛、需要展示思考链、或底座模型不支持原生工具协议。
     */
    STRUCTURED_TEXT,

    /**
     * 原生工具模式 (自然模式)
     * 表现为：利用 LLM 厂商标准的 tool_calls 协议，不带 ReAct 特定标签。
     * 场景：适配现代模型（GPT-4o, Claude 3.5, DeepSeek 等），响应更快且更稳定。
     */
    NATIVE_TOOL
}