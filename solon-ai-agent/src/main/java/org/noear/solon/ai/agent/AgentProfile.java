package org.noear.solon.ai.agent;

import java.io.Serializable;
import java.util.*;

/**
 * 智能体档案 (Agent Profile)
 *
 * <p>描述智能体的能力画像、行为边界与交互契约。</p>
 *
 * @author noear
 * @since 3.8.1
 */
public class AgentProfile implements Serializable {
    private final Set<String> skills = new HashSet<>();
    private final List<String> constraints = new ArrayList<>();
    private String style;
    private final List<String> inputModes = new ArrayList<>();
    private final List<String> outputModes = new ArrayList<>();
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * 校验模态适配性
     */
    public boolean supports(String inputMode, String outputMode) {
        return inputModes.contains(inputMode) && outputModes.contains(outputMode);
    }

    /**
     * 安全获取元数据并提供默认值
     */
    public <T> T getMeta(String key, T defaultValue) {
        Object val = metadata.get(key);
        return (val != null) ? (T) val : defaultValue;
    }

    /**
     * 判断是否具备某种元数据标签 (Tagging)
     */
    public boolean hasTag(String tagName) {
        Object tags = metadata.get("tags");
        if (tags instanceof Collection) {
            return ((Collection<?>) tags).contains(tagName);
        }
        return false;
    }

    public AgentProfile() {
        this.inputModes.add("text");
        this.outputModes.add("text");
    }

    // --- Fluent API ---

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

    // --- Getters ---

    public Set<String> getSkills() { return Collections.unmodifiableSet(skills); }
    public List<String> getConstraints() { return Collections.unmodifiableList(constraints); }
    public String getStyle() { return style; }
    public List<String> getInputModes() { return Collections.unmodifiableList(inputModes); }
    public List<String> getOutputModes() { return Collections.unmodifiableList(outputModes); }
    public Map<String, Object> getMetadata() { return Collections.unmodifiableMap(metadata); }

    /**
     * 转换为格式化字符串，支持国际化
     *
     * @param locale 语言区域
     */
    public String toFormatString(Locale locale) {
        boolean isZh = (locale != null && Locale.CHINESE.getLanguage().equals(locale.getLanguage()));
        StringBuilder sb = new StringBuilder();

        // 技能部分
        if (!skills.isEmpty()) {
            sb.append(isZh ? "擅长技能: " : "Skills: ").append(String.join(", ", skills)).append("; ");
        }

        // 约束部分
        if (!constraints.isEmpty()) {
            sb.append(isZh ? "行为约束: " : "Constraints: ").append(String.join(", ", constraints)).append("; ");
        }

        // 风格部分
        if (style != null && !style.isEmpty()) {
            sb.append(isZh ? "交互风格: " : "Style: ").append(style).append("; ");
        }

        return sb.toString().trim();
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