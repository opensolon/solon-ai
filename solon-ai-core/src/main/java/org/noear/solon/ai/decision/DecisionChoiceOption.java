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
 * 字符串分类候选项及其可选描述。
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class DecisionChoiceOption {
    private final String value;
    private final String description;

    private DecisionChoiceOption(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /** 字符串候选。 */
    public static DecisionChoiceOption of(String value, String description) {
        return new DecisionChoiceOption(Objects.requireNonNull(value, "The choice value is required"), description);
    }

    /** 字符串分类值。 */
    public String getValue() {
        return value;
    }

    /** 可选候选描述。 */
    @Nullable
    public String getDescription() {
        return description;
    }
}

