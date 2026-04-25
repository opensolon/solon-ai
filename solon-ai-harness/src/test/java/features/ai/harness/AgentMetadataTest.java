/*
 * Copyright 2017-2026 noear.org and authors
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
package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubAgent 元数据测试
 *
 * @author bai
 * @since 3.9.5
 */
public class AgentMetadataTest {

    @Test
    public void testParseMetadataWithAllFields() {
        String prompt = "---\n" +
                "name: explore\n" +
                "description: Fast codebase exploration expert\n" +
                "tools: Glob, Grep, Read\n" +
                "model: glm-4-flash\n" +
                "---\n\n" +
                "## 探索代理\n\n" +
                "你是一个快速的代码库探索专家。";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("explore", metadata.getName());
        assertEquals("Fast codebase exploration expert", metadata.getDescription());
        assertEquals("glm-4-flash", metadata.getModel());

        List<String> tools = metadata.getTools();
        assertEquals(3, tools.size());
        assertTrue(tools.contains("Glob"));
        assertTrue(tools.contains("Grep"));
        assertTrue(tools.contains("Read"));
    }

    @Test
    public void testParseMetadataWithPartialFields() {
        String prompt = "---\n" +
                "name: plan\n" +
                "model: glm-4.7\n" +
                "---\n\n" +
                "## 计划代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("plan", metadata.getName());
        assertEquals("glm-4.7", metadata.getModel());
        assertNull(metadata.getDescription());
        assertTrue(metadata.getTools().isEmpty());
    }

    @Test
    public void testParseMetadataWithoutYaml() {
        String prompt = "## 计划代理\n\n" +
                "你是一个软件架构师。";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertNull(metadata.getName());
        assertNull(metadata.getDescription());
        assertNull(metadata.getModel());
        assertTrue(metadata.getTools().isEmpty());
    }

    @Test
    public void testParseAndClean() {
        String prompt = "---\n" +
                "name: bash\n" +
                "model: glm-4-flash\n" +
                "---\n\n" +
                "## Bash 代理\n\n" +
                "你是一个命令行执行专家。";

        AgentDefinition result = AgentDefinition.fromMarkdown(prompt);

        // 验证元数据
        assertEquals("bash", result.getMetadata().getName());
        assertEquals("glm-4-flash", result.getMetadata().getModel());

        // 验证清理后的提示词（不包含 YAML 头部）
        String cleaned = result.getSystemPrompt();
        assertFalse(cleaned.contains("---"));
        assertFalse(cleaned.contains("name: bash"));
        assertTrue(cleaned.contains("## Bash 代理"));
        assertTrue(cleaned.contains("你是一个命令行执行专家"));
    }

    @Test
    public void testParseAndCleanWithoutYaml() {
        String prompt = "## 计划代理\n\n" +
                "你是一个软件架构师。";

        AgentDefinition result = AgentDefinition.fromMarkdown(prompt);

        // 元数据应该为空
        assertNull(result.getMetadata().getName());
        assertNull(result.getMetadata().getModel());

        // 提示词应该保持不变
        assertEquals(prompt, result.getSystemPrompt());
    }

    @Test
    public void testHasModel() {
        AgentDefinition.Metadata metadata1 = new AgentDefinition.Metadata();
        assertFalse(metadata1.hasModel());

        AgentDefinition.Metadata metadata2 = AgentDefinition.fromMarkdown(
                "---\nmodel: glm-4.7\n---\n\n"
        ).getMetadata();

        assertTrue(metadata2.hasModel());
    }

    @Test
    public void testParseWithComments() {
        String prompt = "---\n" +
                "# 这是一个测试代理\n" +
                "name: test\n" +
                "# model: old-model\n" +
                "model: glm-4.7\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertEquals("glm-4.7", metadata.getModel());
    }

    @Test
    public void testParseWithEmptyLines() {
        String prompt = "---\n" +
                "\n" +
                "name: test\n" +
                "\n" +
                "model: glm-4.7\n" +
                "\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertEquals("glm-4.7", metadata.getModel());
    }

    @Test
    public void testRealExploreSubAgentPrompt() {
        String prompt = "---\n" +
                "name: explore\n" +
                "description: Fast codebase exploration expert for finding files and analyzing structure\n" +
                "tools: Glob, Grep, Read\n" +
                "model: glm-4-flash\n" +
                "---\n\n" +
                "## 探索代理\n\n" +
                "你是一个快速的代码库探索专家。你的任务是：\n" +
                "\n" +
                "### 核心能力\n" +
                "- 使用 Glob 工具按模式查找文件（最高效）\n" +
                "- 使用 Grep 工具搜索代码内容\n" +
                "- 使用 Read 工具读取文件内容\n" +
                "- 分析代码结构和架构";

        AgentDefinition result = AgentDefinition.fromMarkdown(prompt);

        // 验证元数据
        AgentDefinition.Metadata metadata = result.getMetadata();
        assertEquals("explore", metadata.getName());
        assertEquals("glm-4-flash", metadata.getModel());
        assertTrue(metadata.hasModel());

        // 验证工具列表
        List<String> tools = metadata.getTools();
        assertEquals(3, tools.size());
        assertTrue(tools.contains("Glob"));
        assertTrue(tools.contains("Grep"));
        assertTrue(tools.contains("Read"));

        // 验证清理后的提示词
        String cleaned = result.getSystemPrompt();
        assertFalse(cleaned.contains("---"));
        assertTrue(cleaned.startsWith("## 探索代理"));
    }

    @Test
    public void testRealPlanSubAgentPrompt() {
        String prompt = "---\n" +
                "name: plan\n" +
                "description: Software architect for designing implementation plans and technical choices\n" +
                "tools: Read\n" +
                "model: glm-4.7\n" +
                "---\n\n" +
                "## 计划代理（软件架构师）\n\n" +
                "你是一个经验丰富的软件架构师。";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("plan", metadata.getName());
        assertEquals("glm-4.7", metadata.getModel());
        assertTrue(metadata.hasModel());
    }

    @Test
    public void testParseDisallowedTools() {
        String prompt = "---\n" +
                "name: test\n" +
                "disallowedTools: Bash, Write, Edit\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertTrue(metadata.hasDisallowedTools());
        assertEquals(3, metadata.getDisallowedTools().size());
        assertTrue(metadata.getDisallowedTools().contains("Bash"));
        assertTrue(metadata.getDisallowedTools().contains("Write"));
        assertTrue(metadata.getDisallowedTools().contains("Edit"));
    }

    @Test
    public void testParsePermissionMode() {
        String prompt = "---\n" +
                "name: test\n" +
                "permissionMode: plan\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertEquals("plan", metadata.getPermissionMode());
        assertTrue(metadata.hasPermissionMode());
    }

    @Test
    public void testParseMaxTurns() {
        String prompt = "---\n" +
                "name: test\n" +
                "maxTurns: 50\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertEquals(Integer.valueOf(50), metadata.getMaxTurns());
        assertTrue(metadata.hasMaxTurns());
    }

    @Test
    public void testParseSkills() {
        String prompt = "---\n" +
                "name: test\n" +
                "skills: commit, review-pr, pdf\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertTrue(metadata.hasSkills());
        assertEquals(3, metadata.getSkills().size());
        assertTrue(metadata.getSkills().contains("commit"));
        assertTrue(metadata.getSkills().contains("review-pr"));
        assertTrue(metadata.getSkills().contains("pdf"));
    }

    @Test
    public void testParseMcpServers() {
        String prompt = "---\n" +
                "name: test\n" +
                "mcpServers: slack, github\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertTrue(metadata.hasMcpServers());
        assertEquals(2, metadata.getMcpServers().size());
        assertTrue(metadata.getMcpServers().contains("slack"));
        assertTrue(metadata.getMcpServers().contains("github"));
    }

    @Test
    public void testParseMemory() {
        String prompt = "---\n" +
                "name: test\n" +
                "memory: project\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertEquals("project", metadata.getMemory());
        assertTrue(metadata.hasMemory());
    }

    @Test
    public void testParseBackground() {
        String prompt = "---\n" +
                "name: test\n" +
                "background: true\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertTrue(metadata.isBackground());
    }

    @Test
    public void testParseIsolation() {
        String prompt = "---\n" +
                "name: test\n" +
                "isolation: worktree\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertEquals("worktree", metadata.getIsolation());
        assertTrue(metadata.hasIsolation());
    }

    @Test
    public void testParseCompleteMetadata() {
        String prompt = "---\n" +
                "name: comprehensive-test\n" +
                "description: A comprehensive test with all metadata fields\n" +
                "tools: Read, Glob, Grep\n" +
                "disallowedTools: Bash, Write\n" +
                "model: glm-4.7\n" +
                "permissionMode: plan\n" +
                "maxTurns: 100\n" +
                "skills: commit, review\n" +
                "mcpServers: slack\n" +
                "memory: user\n" +
                "background: false\n" +
                "isolation: worktree\n" +
                "---\n\n" +
                "## 综合测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("comprehensive-test", metadata.getName());
        assertEquals("A comprehensive test with all metadata fields", metadata.getDescription());
        assertEquals("glm-4.7", metadata.getModel());
        assertEquals("plan", metadata.getPermissionMode());
        assertEquals(Integer.valueOf(100), metadata.getMaxTurns());
        assertEquals("user", metadata.getMemory());
        assertEquals("worktree", metadata.getIsolation());

        assertTrue(metadata.hasModel());
        assertTrue(metadata.hasPermissionMode());
        assertTrue(metadata.hasMaxTurns());
        assertTrue(metadata.hasSkills());
        assertTrue(metadata.hasMcpServers());
        assertTrue(metadata.hasDisallowedTools());
        assertTrue(metadata.hasMemory());
        assertTrue(metadata.hasIsolation());
        assertFalse(metadata.isBackground());

        assertEquals(3, metadata.getTools().size());
        assertEquals(2, metadata.getDisallowedTools().size());
        assertEquals(2, metadata.getSkills().size());
        assertEquals(1, metadata.getMcpServers().size());
    }

    @Test
    public void testInvalidMaxTurnsIgnored() {
        String prompt = "---\n" +
                "name: test\n" +
                "maxTurns: invalid\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertNull(metadata.getMaxTurns());
        assertFalse(metadata.hasMaxTurns());
    }

    @Test
    public void testInvalidBooleanBackgroundIgnored() {
        String prompt = "---\n" +
                "name: test\n" +
                "background: not-a-boolean\n" +
                "---\n\n" +
                "## 测试代理";

        AgentDefinition.Metadata metadata = AgentDefinition.fromMarkdown(prompt).getMetadata();

        assertEquals("test", metadata.getName());
        assertFalse(metadata.isBackground());
        // Boolean.parseBoolean 返回 false for invalid values
    }
}
