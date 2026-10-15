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
 * 有序评分档位；列表位置决定从零开始的分值。
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class DecisionScoreLevel {
    private final String label;
    private final String description;

    private DecisionScoreLevel(String label, String description) {
        this.label = Objects.requireNonNull(label, "The score label is required");
        this.description = description;
    }

    /** 仅使用标签描述档位。 */
    public static DecisionScoreLevel of(String label) {
        return of(label, null);
    }

    /** 使用标签和独立描述定义档位。 */
    public static DecisionScoreLevel of(String label, String description) {
        return new DecisionScoreLevel(label, description);
    }

    /** 档位标签。 */
    public String getLabel() {
        return label;
    }

    /** 可选档位描述。 */
    @Nullable
    public String getDescription() {
        return description;
    }
}

