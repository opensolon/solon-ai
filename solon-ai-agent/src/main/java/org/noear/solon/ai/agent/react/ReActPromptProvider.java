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

import org.noear.solon.ai.agent.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.Locale;

/**
 * ReAct 提示词提供者
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8")
public interface ReActPromptProvider {
    default Locale getLocale(){
        return Locale.CHINESE;
    }

    /**
     * 为当前上下文生成最终的系统提示词（执行模板渲染）
     *
     * @param trace   协作溯源
     * @param context 流程上下文
     */
    default String getSystemPromptFor(ReActTrace trace, FlowContext context) {
        return SnelUtil.render(getSystemPrompt(trace), context);
    }

    /**
     * 获取系统提示词
     */
    String getSystemPrompt(ReActTrace trace);

    /**
     * 获取角色
     */
    String getRole(ReActTrace trace);

    /**
     * 获取指令
     */
    String getInstruction(ReActTrace trace);
}
