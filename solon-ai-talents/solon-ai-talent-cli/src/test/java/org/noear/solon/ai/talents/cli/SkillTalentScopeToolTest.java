/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.talents.mount.catalog.SkillCatalog;
import org.noear.solon.ai.talents.mount.catalog.SkillContent;
import org.noear.solon.ai.talents.mount.catalog.SkillDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * selected 视野在工具执行层的传导：
 * 枚举/发现类工具（skilllist / skillsearch）按视野收窄，且隐式参数不暴露给模型。
 */
class SkillTalentScopeToolTest {
    private static SkillTalent talent() {
        return new SkillTalent(new ScopeFakeCatalog());
    }

    private static Map<String, FunctionTool> toolsOf(SkillTalent talent) {
        Map<String, FunctionTool> tools = new HashMap<>();
        for (FunctionTool t : talent.getToolAry()) {
            tools.put(t.name(), t);
        }
        return tools;
    }

    private static Map<String, Object> args(Object selected) {
        Map<String, Object> args = new HashMap<>();
        if (selected != null) {
            args.put(SkillTalent.CTX_SKILLS_SELECTED, selected);
        }
        return args;
    }

    @Test
    void implicitSelectedParamIsInvisibleInSchema() {
        for (FunctionTool t : talent().getToolAry()) {
            // 隐式参数不能出现在 schema 里，否则模型会看到并试图填写它
            assertFalse(t.inputSchema().contains("_skills_selected"),
                    t.name() + " schema leaks implicit param");
        }
    }

    @Test
    void skilllistNarrowsToSelectedScope() throws Throwable {
        FunctionTool skilllist = toolsOf(talent()).get("skilllist");

        String out = (String) skilllist.handle(args(Arrays.asList("@m/a")));
        assertTrue(out.contains("@m/a"));
        assertFalse(out.contains("@m/b"));
    }

    @Test
    void skilllistWithoutSelectionShowsAll() throws Throwable {
        FunctionTool skilllist = toolsOf(talent()).get("skilllist");

        String out = (String) skilllist.handle(args(null));
        assertTrue(out.contains("@m/a"));
        assertTrue(out.contains("@m/b"));
    }

    @Test
    void skillsearchNarrowsToSelectedScope() throws Throwable {
        FunctionTool skillsearch = toolsOf(talent()).get("skillsearch");

        Map<String, Object> callArgs = args(Arrays.asList("@m/a"));
        callArgs.put("query", "技能");

        String out = (String) skillsearch.handle(callArgs);
        assertTrue(out.contains("@m/a"));
        assertFalse(out.contains("@m/b"));
    }

    @Test
    void skillreadStillReachesSkillOutsideScope() throws Throwable {
        // 视野不是权限：点名读取必须放行（引用链不断）
        FunctionTool skillread = toolsOf(talent()).get("skillread");

        Map<String, Object> callArgs = args(Arrays.asList("@m/a"));
        callArgs.put("name", "@m/b");

        String out = (String) skillread.handle(callArgs);
        assertTrue(out.contains("B 的正文"));
    }

    // ---------- 测试替身 ----------

    private static class ScopeFakeCatalog implements SkillCatalog {
        private final Map<String, SkillDescriptor> skills = new LinkedHashMap<>();
        private final Map<String, SkillContent> contents = new LinkedHashMap<>();

        ScopeFakeCatalog() {
            add(new SkillDescriptor("@m/a", "a", "技能 A", "1.0"), "A 的正文");
            add(new SkillDescriptor("@m/b", "b", "技能 B", "1.0"), "B 的正文");
        }

        private void add(SkillDescriptor descriptor, String text) {
            skills.put(descriptor.getId(), descriptor);
            contents.put(descriptor.getId(),
                    new SkillContent(descriptor, text, "\n<skill_content name=\"" + descriptor.getName()
                            + "\">\n" + text + "\n</skill_content>\n"));
        }

        @Override
        public void refresh() {
        }

        @Override
        public int getSkillCount() {
            return skills.size();
        }

        @Override
        public Collection<SkillDescriptor> getDescriptors() {
            return new ArrayList<>(skills.values());
        }

        @Override
        public Collection<SkillDescriptor> searchDescriptors(String query) {
            // 粗略匹配：返回全部，用于验证视野收窄
            return new ArrayList<>(skills.values());
        }

        @Override
        public SkillDescriptor getDescriptor(String name) {
            return skills.get(name);
        }

        @Override
        public SkillContent readContent(String name) {
            SkillContent direct = contents.get(name);
            if (direct != null) {
                return direct;
            }
            return contents.get("@" + name);
        }

        @Override
        public boolean isAllowed(SkillDescriptor descriptor) {
            return descriptor != null && skills.containsKey(descriptor.getId());
        }
    }
}
