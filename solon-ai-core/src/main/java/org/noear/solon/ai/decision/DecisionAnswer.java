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

import org.noear.solon.Utils;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.*;

/**
 * 统一决策答案，包括逐题拒答；仅对应类型的数值有效。
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public class DecisionAnswer {
    private final String type;
    private final String choice;
    private final Double probability;
    private final Double score;
    private final List<DecisionProbability> probabilities;
    private final Double confidence;

    private DecisionAnswer(String type, String choice, Double probability, Double score,
                           List<DecisionProbability> probabilities, Double confidence) {
        this.type = type;
        this.choice = choice;
        this.probability = probability;
        this.score = score;
        this.probabilities = probabilities == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(probabilities));
        this.confidence = confidence;
        if (confidence != null && (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)) {
            throw new DecisionException("The confidence must be between 0 and 1");
        }
        Set<Object> values = new HashSet<>();
        for (DecisionProbability entry : this.probabilities) {
            if (entry == null || !values.add(entry.getValue())) {
                throw new DecisionException("Probability values must be nonnull and unique");
            }
            if ("score".equals(type) != (entry.getValue() instanceof Integer)) {
                throw new DecisionException("The probability value does not match the answer type");
            }
        }
    }

    /** 字符串分类答案。 */
    public static DecisionAnswer choice(String choice, List<DecisionProbability> probabilities, double confidence) {
        if (choice == null) {
            throw new DecisionException("A choice answer requires a string value");
        }
        DecisionAnswer answer = new DecisionAnswer("choice", choice, null, null, probabilities, confidence);
        if (Utils.isEmpty(answer.probabilities)
                || answer.probabilities.stream().noneMatch(p -> p.getValue().equals(choice))) {
            throw new DecisionException("The choice must occur in its probability distribution");
        }
        return answer;
    }

    /** 条件成立的概率。 */
    public static DecisionAnswer predicate(double probability) {
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
            throw new DecisionException("The predicate probability must be between 0 and 1");
        }
        return new DecisionAnswer("predicate", null, probability, null, null, null);
    }

    /** 评分答案；评分范围由返回的档位下标决定。 */
    public static DecisionAnswer score(double score, List<DecisionProbability> probabilities, double confidence) {
        DecisionAnswer answer = new DecisionAnswer("score", null, null, score, probabilities, confidence);
        if (!Double.isFinite(score) || score < 0 || Utils.isEmpty(answer.probabilities)) {
            throw new DecisionException("A score answer requires a finite nonnegative score and ordered levels");
        }
        for (DecisionProbability entry : answer.probabilities) {
            if ((Integer) entry.getValue() >= answer.probabilities.size()) {
                throw new DecisionException("Score probability indices must cover the levels from zero");
            }
        }
        if (score > answer.probabilities.size() - 1) {
            throw new DecisionException("The score is outside the level range");
        }
        return answer;
    }

    /** 单个问题被模型拒答，其他问题的答案仍然有效。 */
    public static DecisionAnswer refusal() {
        return new DecisionAnswer("refusal", null, null, null, null, null);
    }

    /** 答案类型：predicate、choice、score 或 refusal。 */
    public String getType() {
        return type;
    }

    /** 字符串分类值，其他答案类型为 null。 */
    @Nullable
    public String getChoice() {
        return choice;
    }

    /** 条件成立的概率，其他类型为 null。 */
    @Nullable
    public Double getProbability() {
        return probability;
    }

    /** 从零开始的概率加权评分，其他类型为 null。 */
    @Nullable
    public Double getScore() {
        return score;
    }

    /** 有序的类型化概率分布，判断和拒答为空列表。 */
    public List<DecisionProbability> getProbabilities() {
        return probabilities;
    }

    /** 厂商原始 confidence，公式不可跨厂商比较；判断和拒答为 null。 */
    @Nullable
    public Double getConfidence() {
        return confidence;
    }

    @Override
    public String toString() {
        return "{type='" + type + "', choice=" + choice + ", probability=" + probability
                + ", score=" + score + ", probabilities=" + probabilities + ", confidence=" + confidence + '}';
    }
}
