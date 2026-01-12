package org.noear.solon.ai.agent.simple;

import org.noear.solon.core.util.SnelUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.lang.Preview;

import java.util.function.Function;

/**
 * 简单智能体系统提示词提供者
 *
 * <p>支持基于 Snel 模板引擎的动态渲染，允许在执行期从 FlowContext 注入变量</p>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class SimpleSystemPrompt {
    private final Function<FlowContext, String> roleProvider;
    private final Function<FlowContext, String> instructionProvider;

    public SimpleSystemPrompt(Function<FlowContext, String> roleProvider, Function<FlowContext, String> instructionProvider) {
        this.roleProvider = roleProvider;
        this.instructionProvider = instructionProvider;
    }

    /**
     * 获取完整的系统提示词（执行 Snel 渲染）
     */
    public String getSystemPromptFor(FlowContext context) {
        String rawPrompt = getSystemPrompt(context);
        if (context == null || rawPrompt == null) {
            return rawPrompt;
        }

        // 支持模板渲染，例如：你正在处理来自 ${user} 的请求
        return SnelUtil.render(rawPrompt, context.model());
    }

    /**
     * 组合角色和指令
     */
    public String getSystemPrompt(FlowContext context) {
        StringBuilder sb = new StringBuilder();
        String role = getRole(context);
        String inst = getInstruction(context);

        if (role != null) {
            sb.append("## 角色设定\n").append(role).append("\n\n");
        }
        if (inst != null) {
            sb.append("## 执行指令\n").append(inst);
        }
        return sb.toString();
    }

    public String getRole(FlowContext context) {
        return roleProvider != null ? roleProvider.apply(context) : null;
    }

    public String getInstruction(FlowContext context) {
        return instructionProvider != null ? instructionProvider.apply(context) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Function<FlowContext, String> roleProvider;
        private Function<FlowContext, String> instructionProvider;

        public Builder role(String role) {
            return role(ctx -> role);
        }

        public Builder instruction(String instruction) {
            return instruction(ctx -> instruction);
        }

        public Builder role(Function<FlowContext, String> roleProvider) {
            this.roleProvider = roleProvider;
            return this;
        }

        public Builder instruction(Function<FlowContext, String> instructionProvider) {
            this.instructionProvider = instructionProvider;
            return this;
        }

        public SimpleSystemPrompt build() {
            return new SimpleSystemPrompt(roleProvider, instructionProvider);
        }
    }
}