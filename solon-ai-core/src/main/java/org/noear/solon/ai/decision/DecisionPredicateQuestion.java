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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判断条件成立概率的问题，不自动转换为业务布尔值。
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class DecisionPredicateQuestion extends DecisionQuestion {
    // key: false / true，value: 条件不成立 / 成立的描述；仅 Ollama 支持
    private final Map<String, String> criteria = new LinkedHashMap<>();

    private DecisionPredicateQuestion(String instructions) {
        super("predicate", instructions);
    }

    /** 创建条件成立概率问题。 */
    public static DecisionPredicateQuestion of(String instructions) {
        return new DecisionPredicateQuestion(instructions);
    }

    /** 复制并替换 Ollama 专有的 false/true 描述；null 清空，OpenAI 方言忽略该标准。 */
    public DecisionPredicateQuestion criteria(Map<String, String> criteria) {
        Map<String, String> values = criteria == null ? Collections.emptyMap() : new LinkedHashMap<>(criteria);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if ((!"false".equals(entry.getKey()) && !"true".equals(entry.getKey()))
                    || entry.getValue() == null) {
                throw new DecisionException("Predicate criteria require false/true string descriptions");
            }
        }
        this.criteria.clear();
        this.criteria.putAll(values);
        return this;
    }

    /** Ollama 专有判断标准的只读视图。 */
    public Map<String, String> getCriteria() {
        return Collections.unmodifiableMap(criteria);
    }

    @Override
    DecisionPredicateQuestion copy() {
        DecisionPredicateQuestion question = of(getInstructions());
        question.criteria.putAll(criteria);
        return question;
    }
}
