package org.noear.solon.ai.agent;

import java.io.Serializable;
import java.util.*;

/**
 * 智能体档案 (Agent Profile)
 *
 * <p>作为智能体核心体系的结构化增量，描述其能力画像与交互契约。</p>
 * <p>主要用于：
 * <ul>
 * <li><b>能力发现：</b>Supervisor 根据 skills 进行语义路由。</li>
 * <li><b>交互协商：</b>协议层根据 modes 进行内容适配。</li>
 * <li><b>元数据透传：</b>通过 metadata 传递成本、版本等非功能性指标。</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 3.8.1
 */
public class AgentProfile implements Serializable {
    /**
     * 能力领域 (Skills): 擅长的领域标签，如 ["java", "sql", "translate"]
     */
    private final Set<String> skills = new HashSet<>();

    /**
     * 输入模态约束: 声明支持的输入格式，如 ["text", "image"]
     */
    private final List<String> inputModes = new ArrayList<>();

    /**
     * 输出模态约束: 声明支持的输出格式，如 ["text", "json", "code"]
     */
    private final List<String> outputModes = new ArrayList<>();

    /**
     * 扩展元数据 (Metadata): 记录版本、成本系数、响应等级等
     */
    private final Map<String, Object> metadata = new HashMap<>();

    public AgentProfile() {
        // 默认初始化为最通用的文本模态
        this.inputModes.add("text");
        this.outputModes.add("text");
    }

    // --- Fluent API (链式调用) ---

    /**
     * 添加能力标签
     */
    public AgentProfile skillAdd(String... skills) {
        if (skills != null) {
            this.skills.addAll(Arrays.asList(skills));
        }
        return this;
    }

    /**
     * 添加交互模态
     *
     * @param input  输入模态（为 null 则不添加）
     * @param output 输出模态（为 null 则不添加）
     */
    public AgentProfile modeAdd(String input, String output) {
        if (input != null) {
            this.inputModes.add(input);
        }
        if (output != null) {
            this.outputModes.add(output);
        }
        return this;
    }

    /**
     * 设置元数据
     */
    public AgentProfile metaPut(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    // --- Getters ---

    public Set<String> getSkills() {
        return Collections.unmodifiableSet(skills);
    }

    public List<String> getInputModes() {
        return Collections.unmodifiableList(inputModes);
    }

    public List<String> getOutputModes() {
        return Collections.unmodifiableList(outputModes);
    }

    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @Override
    public String toString() {
        return "AgentProfile{" +
                "skills=" + skills +
                ", inputModes=" + inputModes +
                ", outputModes=" + outputModes +
                ", metadata=" + metadata +
                '}';
    }
}