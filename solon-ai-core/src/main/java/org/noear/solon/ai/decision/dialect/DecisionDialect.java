/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.decision.dialect;

import org.noear.solon.ai.AiModelDialect;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.decision.DecisionConfig;
import org.noear.solon.ai.decision.DecisionOptions;
import org.noear.solon.ai.decision.DecisionQuestion;
import org.noear.solon.ai.decision.DecisionResponse;
import org.noear.solon.lang.Preview;

import java.util.List;
import java.util.Map;

/**
 * 决策模型方言
 *
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public interface DecisionDialect extends AiModelDialect {
    /**
     * 是否为默认方言
     */
    default boolean isDefault() {
        return false;
    }

    /**
     * 匹配接口规范或提供者
     */
    boolean matched(DecisionConfig config);

    /**
     * 校验并构建请求 JSON
     *
     * @param config 模型配置
     * @param options 请求选项
     * @param input 共享输入
     * @param questions 问题名与问题定义
     * @param images 所有问题共享的有序图片
     */
    String buildRequestJson(DecisionConfig config, DecisionOptions options, String input,
                            Map<String, DecisionQuestion> questions, List<ImageBlock> images);

    /**
     * 按原请求解析并归一化响应；校验问题、候选和档位完整对应，协议错误返回带异常的响应。
     * 方言可共享，但本次请求的编码映射必须为局部状态。
     *
     * @param questions 构建请求时使用的不可修改的问题快照
     */
    DecisionResponse parseResponseJson(DecisionConfig config, Map<String, DecisionQuestion> questions, String respJson);
}
