package org.noear.solon.ai.harness.command.builtin;


import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.harness.i18n.HarnessI18nKeys;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.lang.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * /model 命令（多子命令）
 *
 * @author noear
 * @since 4.2.0
 */
public class ModelCommand extends AbsCommand {
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
    public String description(@Nullable Locale locale) {
        return HarnessI18n.get(locale, HarnessI18nKeys.COMMAND_MODEL_DESCRIPTION);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{
                "/model",
                "/model help",
                "/model <name>"
        };
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) throws Exception {
        String flag = input.argAt(0);

        if ("ls".equals(flag) || flag == null || flag.isEmpty()) {
            String currentModel = engine.getSessionModel(input.getSession());
            currentModel = engine.getModelOrDef(currentModel).getNameOrModel();

            for (String line : formatModels(engine.getModels(), currentModel)) {
                output.println(output.color(line));
            }
            output.println(output.color(DIM + "\nUsage: /model <name>" + RESET));
        } else if ("help".equals(flag)) {
            output.println(output.color(BOLD + "/model" + RESET + " - Model management"));
            output.println(output.color(DIM + "  /model" + RESET + "          List all available models"));
            output.println(output.color(DIM + "  /model ls" + RESET + "       List all available models"));
            output.println(output.color(DIM + "  /model <name>" + RESET + "   Switch to the specified model"));
        } else {
            if (engine.getModelOrNil(flag) == null) {
                output.println(output.color(RED + "Model not found: " + RESET + BOLD + flag + RESET));
                output.println(output.color(DIM + "Use '/model' to see available models." + RESET));
            } else {
                engine.setSessionModel(input.getSession(), flag);
                input.getSession().updateSnapshot();
                output.println(output.color(GREEN + "Model switched to: " + RESET + BOLD + flag + RESET));
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