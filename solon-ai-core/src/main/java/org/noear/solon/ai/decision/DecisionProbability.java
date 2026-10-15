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

import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.Objects;

/**
 * 分类值或评分下标对应的概率；方言按原请求候选或档位顺序归一化。
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class DecisionProbability {
    private final Object value;
    private final double probability;
    private final String label;

    private DecisionProbability(Object value, double probability, String label) {
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
            throw new DecisionException("The probability must be between 0 and 1");
        }
        this.value = Objects.requireNonNull(value, "The probability value is required");
        this.probability = probability;
        this.label = label;
    }

    /** 分类值对应的概率。 */
    public static DecisionProbability choice(String value, double probability) {
        return new DecisionProbability(value, probability, null);
    }

    /** 评分档位下标及可选标签对应的概率。 */
    public static DecisionProbability score(int index, String label, double probability) {
        if (index < 0) {
            throw new DecisionException("The score index must be nonnegative");
        }
        return new DecisionProbability(index, probability, label);
    }

    /** 原始值：分类为 String，评分下标为 Integer。 */
    public Object getValue() {
        return value;
    }

    /** 概率，范围 0–1。 */
    public double getProbability() {
        return probability;
    }

    /** 可选评分标签。 */
    @Nullable
    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return "{value=" + value + ", probability=" + probability + ", label=" + label + '}';
    }
}

