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
import java.util.Map;
import java.util.Objects;

/**
 * 字符串分类问题，候选按添加顺序排列。
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class DecisionChoiceQuestion extends DecisionQuestion {
    private final List<DecisionChoiceOption> choices = new ArrayList<>();

    private DecisionChoiceQuestion(String instructions) {
        super("choice", instructions);
    }

    /** 创建分类问题，候选值和选中结果均为字符串。 */
    public static DecisionChoiceQuestion of(String instructions) {
        return new DecisionChoiceQuestion(instructions);
    }

    /** 添加无独立描述的候选。 */
    public DecisionChoiceQuestion add(String value) {
        return add(value, null);
    }

    /** 添加候选值及其可选描述。 */
    public DecisionChoiceQuestion add(String value, String description) {
        DecisionChoiceOption choice = DecisionChoiceOption.of(value, description);
        if (choice.getValue().trim().isEmpty()) {
            throw new DecisionException("Choice candidates must be unique and nonblank");
        }
        for (DecisionChoiceOption existing : choices) {
            if (existing.getValue().equals(choice.getValue())) {
                throw new DecisionException("Choice candidates must be unique and nonblank");
            }
        }
        choices.add(choice);
        return this;
    }

    /** 按 Map 遍历顺序添加候选；key 为候选值，value 为可选描述。 */
    public DecisionChoiceQuestion add(Map<String, String> choices) {
        Objects.requireNonNull(choices, "The choice candidates are required");
        for (Map.Entry<String, String> entry : choices.entrySet()) {
            add(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /** 候选的只读有序视图。 */
    public List<DecisionChoiceOption> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    @Override
    DecisionChoiceQuestion copy() {
        DecisionChoiceQuestion question = of(getInstructions());
        question.choices.addAll(choices);
        return question;
    }
}
