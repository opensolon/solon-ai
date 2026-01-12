/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.agent;

import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.*;

/**
 * 智能体档案 (Agent Profile)
 *
 * <p>核心职责：定义 Agent 的身份标识、能力边界（Skills）、模态约束（Modes）与交互风格。</p>
 * <p>它是智能体路由（Routing）与协作选型的重要元数据载体。</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class AgentProfile implements Serializable {
    /** 技能集：决定 Agent 能处理什么业务（如：代码审计、数据绘图） */
    private final Set<String> skills = new HashSet<>();
    /** 行为约束：定义 Agent 不能做什么（如：禁止输出隐私信息） */
    private final List<String> constraints = new ArrayList<>();
    /** 交互风格：定义回复的语调（如：专业严谨、幽默风趣） */
    private String style;
    /** 输入模态：支持的媒介类型（如：text, image, audio） */
    private final List<String> inputModes = new ArrayList<>();
    /** 输出模态：产出的媒介类型 */
    private final List<String> outputModes = new ArrayList<>();
    /** 扩展元数据：用于存放自定义配置（如：API 权重、地理位置） */
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * 校验模态适配性（用于路由判断）
     */
    public boolean supports(String inputMode, String outputMode) {
        return inputModes.contains(inputMode) && outputModes.contains(outputMode);
    }

    /**
     * 安全获取元数据（泛型支持）
     */
    @SuppressWarnings("unchecked")
    public <T> T getMeta(String key, T defaultValue) {
        Object val = metadata.get(key);
        return (val != null) ? (T) val : defaultValue;
    }


    public AgentProfile() {
        // 默认初始化为基础文本模态
        this.inputModes.add("text");
        this.outputModes.add("text");
    }

    // --- Fluent API (链式配置) ---

    public AgentProfile skillAdd(String... skills) {
        if (skills != null) this.skills.addAll(Arrays.asList(skills));
        return this;
    }

    public AgentProfile constraintAdd(String... constraints) {
        if (constraints != null) this.constraints.addAll(Arrays.asList(constraints));
        return this;
    }

    public AgentProfile style(String style) {
        this.style = style;
        return this;
    }

    public AgentProfile modeAdd(String input, String output) {
        if (input != null) this.inputModes.add(input);
        if (output != null) this.outputModes.add(output);
        return this;
    }

    public AgentProfile metaPut(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    // --- Getters (只读包装) ---

    public Set<String> getSkills() { return Collections.unmodifiableSet(skills); }
    public List<String> getConstraints() { return Collections.unmodifiableList(constraints); }
    public String getStyle() { return style; }
    public List<String> getInputModes() { return Collections.unmodifiableList(inputModes); }
    public List<String> getOutputModes() { return Collections.unmodifiableList(outputModes); }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }

    /**
     * 转换为格式化描述字符串（常用于注入 SystemPrompt 帮助 AI 理解队友能力）
     *
     * @param locale 语言区域（支持中英自适应）
     */
    public String toFormatString(Locale locale) {
        boolean isZh = (locale != null && Locale.CHINESE.getLanguage().equals(locale.getLanguage()));
        List<String> segments = new ArrayList<>();

        // 1. 模态声明 (A2A 路由基础)
        if (!inputModes.contains("image") && inputModes.contains("text") && inputModes.size() == 1) {
            segments.add(isZh ? "输入限制: 仅限文本" : "Input: Text only");
        } else {
            segments.add((isZh ? "输入模态: " : "Input Modes: ") + String.join(", ", inputModes));
        }

        if (outputModes.contains("image")) {
            segments.add(isZh ? "产出: 包含图像" : "Outputs: Images");
        }

        // 2. 技能部分
        if (!skills.isEmpty()) {
            segments.add((isZh ? "擅长技能: " : "Skills: ") + String.join(", ", skills));
        }

        // 3. 约束部分
        if (!constraints.isEmpty()) {
            segments.add((isZh ? "行为约束: " : "Constraints: ") + String.join(", ", constraints));
        }

        // 4. 风格部分
        if (style != null && !style.isEmpty()) {
            segments.add((isZh ? "交互风格: " : "Style: ") + style);
        }

        return String.join(" | ", segments);
    }

    @Override
    public String toString() {
        return "AgentProfile{" +
                "skills=" + skills +
                ", constraints=" + constraints +
                ", style='" + style + '\'' +
                ", inputModes=" + inputModes +
                ", outputModes=" + outputModes +
                ", metadata=" + metadata +
                '}';
    }
}