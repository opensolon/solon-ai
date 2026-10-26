package org.noear.solon.ai.harness.command.builtin;


import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.Command;
import org.noear.solon.ai.harness.command.CommandContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * /model 命令（多子命令）
 *
 * @author noear
 * @since 2026.4.28
 */
public class ModelCommand implements Command {
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "模型管理 (ls, help, <name>)";
    }

    @Override
    public String[] examples() {
        return new String[]{
                "/model",
                "/model help",
                "/model <name>"
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx) throws Exception {
        String flag = ctx.argAt(0);

        if ("ls".equals(flag) || flag == null || flag.isEmpty()) {
            String currentModel = ctx.getSession().getContext().getAs(HarnessEngine.CTX_MODEL_SELECTED);
            currentModel = engine.getModelOrDef(currentModel).getNameOrModel();

            for (String line : formatModels(engine.getModels(), currentModel)) {
                ctx.println(ctx.color(line));
            }
            ctx.println(ctx.color(DIM + "\nUsage: /model <name>" + RESET));
        } else if ("help".equals(flag)) {
            ctx.println(ctx.color(BOLD + "/model" + RESET + " - Model management"));
            ctx.println(ctx.color(DIM + "  /model" + RESET + "          List all available models"));
            ctx.println(ctx.color(DIM + "  /model ls" + RESET + "       List all available models"));
            ctx.println(ctx.color(DIM + "  /model <name>" + RESET + "   Switch to the specified model"));
        } else {
            if (engine.getModelOrNil(flag) == null) {
                ctx.println(ctx.color(RED + "Model not found: " + RESET + BOLD + flag + RESET));
                ctx.println(ctx.color(DIM + "Use '/model' to see available models." + RESET));
            } else {
                ctx.getSession().getContext().put(HarnessEngine.CTX_MODEL_SELECTED, flag);
                ctx.getSession().updateSnapshot();
                ctx.println(ctx.color(GREEN + "Model switched to: " + RESET + BOLD + flag + RESET));
            }
        }
    }

    /**
     * 渲染模型列表（仅含启用模型，每行只输出 nameOrModel，当前模型带 active 标记）
     */
    public static List<String> formatModels(Collection<? extends ChatConfig> models, String currentModel) {
        List<String> lines = new ArrayList<>();
        lines.add(BOLD + "Models:" + RESET);

        for (ChatConfig m : models) {
            if (m == null || !m.isEnabled()) {
                continue;
            }
            String name = m.getNameOrModel();
            String suffix = name.equals(currentModel) ? " " + GREEN + "(active)" + RESET : "";
            lines.add("  " + name + suffix);
        }

        return lines;
    }
}