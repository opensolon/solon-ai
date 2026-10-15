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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 有序评分问题，档位添加顺序决定从零开始的分值。
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class DecisionScoreQuestion extends DecisionQuestion {
    private final List<DecisionScoreLevel> levels = new ArrayList<>();

    private DecisionScoreQuestion(String instructions) {
        super("score", instructions);
    }

    /** 创建有序评分问题。 */
    public static DecisionScoreQuestion of(String instructions) {
        return new DecisionScoreQuestion(instructions);
    }

    /** 按顺序添加仅有标签的档位。 */
    public DecisionScoreQuestion add(String label) {
        return add(label, null);
    }

    /** 按顺序添加档位标签及其可选描述。 */
    public DecisionScoreQuestion add(String label, String description) {
        levels.add(DecisionScoreLevel.of(label, description));
        return this;
    }

    /** 档位的只读有序视图，下标从零开始。 */
    public List<DecisionScoreLevel> getLevels() {
        return Collections.unmodifiableList(levels);
    }

    @Override
    DecisionScoreQuestion copy() {
        DecisionScoreQuestion question = of(getInstructions());
        question.levels.addAll(levels);
        return question;
    }
}
