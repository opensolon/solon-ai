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
package org.noear.solon.ai.decision;

import org.noear.solon.lang.Preview;

/**
 * 供应商无关的决策问题，具体类型分别定义分类、判断概率和有序评分。
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public abstract class DecisionQuestion {
    private final String type;
    private final String instructions;

    DecisionQuestion(String type, String instructions) {
        if (instructions == null || instructions.trim().isEmpty()) {
            throw new DecisionException("The question instructions are required");
        }
        this.type = type;
        this.instructions = instructions;
    }

    /** 公共问题类型。 */
    public String getType() {
        return type;
    }

    /** 问题说明文本；结构化内容由调用方先序列化为 JSON 文本。 */
    public String getInstructions() {
        return instructions;
    }

    /** 复制本次调用使用的问题定义，隔离后续的链式修改。 */
    abstract DecisionQuestion copy();
}
