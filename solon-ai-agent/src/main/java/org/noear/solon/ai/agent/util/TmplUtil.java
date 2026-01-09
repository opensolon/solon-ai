package org.noear.solon.ai.agent.util;

import org.noear.solon.expression.snel.SnEL;
import org.noear.solon.flow.FlowContext;

/**
 * 模版解析工具类
 * <p>用于对 Agent 指令或描述中的占位符进行变量动态替换</p>
 *
 * @author noear 2026/1/9 created
 */
public class TmplUtil {
    /**
     * 渲染模板（将占位符替换为上下文中的实际值）
     */
    public static String render(String tmpl, FlowContext context) {
        if (tmpl == null || tmpl.isEmpty()) {
            return tmpl;
        }

        // 同时支持 #{} 和 ${} 两种常见的占位符风格
        if (tmpl.contains("#{") || tmpl.contains("${")) {
            if (context != null && context.model() != null) {
                return SnEL.evalTmpl(tmpl, context.model());
            }
        }

        return tmpl;
    }
}