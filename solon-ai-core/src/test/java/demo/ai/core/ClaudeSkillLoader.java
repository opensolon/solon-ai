package demo.ai.core;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillDesc;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude Code Skill 目录加载器
 */
public class ClaudeSkillLoader {

    /**
     * 1. 扫描入口：输入 skills 根目录，输出 Skill 列表
     */
    public List<Skill> loadSkills(File rootDir) {
        List<Skill> skills = new ArrayList<>();
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            return skills;
        }

        File[] subFiles = rootDir.listFiles();
        if (subFiles != null) {
            for (File file : subFiles) {
                if (file.isDirectory()) {
                    // 每个子目录尝试转为一个 Skill
                    Skill skill = convertToSkill(file);
                    if (skill != null) {
                        skills.add(skill);
                    }
                }
            }
        }
        return skills;
    }

    /**
     * 2. 核心转换：将具体的目录转为 Solon AI Skill
     */
    public Skill convertToSkill(File skillDir) {
        File skillFile = new File(skillDir, "SKILL.md");
        if (!skillFile.exists()) {
            return null; // 必须包含 SKILL.md
        }

        try {
            String content = new String(Files.readAllBytes(skillFile.toPath()));

            // 解析 YAML Frontmatter (--- name: xxx ---)
            String name = parseYamlField(content, "name");
            String description = parseYamlField(content, "description");

            // 如果 YAML 没写，默认用目录名
            if (Utils.isEmpty(name)) name = skillDir.getName();

            // 提取 Markdown 正文（去掉 Frontmatter 部分）
            String instruction = content.replaceFirst("(?s)^---.*?---", "").trim();

            // 构建 SkillDesc
            SkillDesc.Builder builder = SkillDesc.builder(name)
                    .description(description)
                    .instruction(instruction);

            // 扫描目录下的脚本文件并转为 Tool
            scanScriptsToTools(skillDir, builder);

            return builder.build();
        } catch (Exception e) {
            // 这里可以记录 log
            return null;
        }
    }

    private void scanScriptsToTools(File skillDir, SkillDesc.Builder builder) {
        File[] files = skillDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String fileName = file.getName();
            // 简单识别常见的脚本后缀
            if (fileName.endsWith(".sh") || fileName.endsWith(".py") || fileName.endsWith(".js")) {
                String toolName = fileName.substring(0, fileName.lastIndexOf("."));

                // 生成一个 FunctionTool
                FunctionToolDesc tool = new FunctionToolDesc(toolName)
                        .description("执行脚本: " + fileName)
                        .stringParamAdd("args", "传递给脚本的参数字符串")
                        .doHandle(args -> {
                            String inputArgs = (String) args.get("args");
                            // 暂时作为 String 输出，后期可在此实现 Runtime.exec
                            return "Skill脚本待执行路径: " + file.getAbsolutePath() + " 参数: " + inputArgs;
                        });

                builder.toolAdd(tool);
            }
        }
    }

    private String parseYamlField(String content, String field) {
        Pattern pattern = Pattern.compile("^" + field + ":\\s*(.*)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim().replaceAll("^['\"]|['\"]$", "");
        }
        return null;
    }
}