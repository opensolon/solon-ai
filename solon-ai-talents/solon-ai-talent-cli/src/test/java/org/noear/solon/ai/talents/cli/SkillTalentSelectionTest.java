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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.talents.mount.catalog.SkillCatalog;
import org.noear.solon.ai.talents.mount.catalog.SkillContent;
import org.noear.solon.ai.talents.mount.catalog.SkillDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能视野：清单收窄、预加载与空语义。
 */
class SkillTalentSelectionTest {
    private static SkillTalent talent(int preloadLimit) {
        SkillTalent talent = new SkillTalent(new FakeCatalog());
        return talent.preloadCharLimit(preloadLimit);
    }

    private static Prompt selecting(String... names) {
        Prompt prompt = Prompt.of("hi");
        prompt.attrPut(SkillTalent.CTX_SKILLS_SELECTED, Arrays.asList(names));
        return prompt;
    }

    @Test
    void withoutSelectionShowsAllSkills() {
        String instruction = talent(0).getInstruction(Prompt.of("hi"));

        assertNotNull(instruction);
        assertTrue(instruction.contains("@m/a"));
        assertTrue(instruction.contains("@m/b"));
    }

    @Test
    void selectionNarrowsListByFullId() {
        String instruction = talent(0).getInstruction(selecting("@m/a"));

        assertTrue(instruction.contains("@m/a"));
        assertFalse(instruction.contains("name=\"@m/b\""));
    }

    @Test
    void selectionMatchesShortName() {
        String instruction = talent(0).getInstruction(selecting("b"));

        assertTrue(instruction.contains("@m/b"));
        assertFalse(instruction.contains("name=\"@m/a\""));
    }

    @Test
    void selectionMatchesIdWrittenWithoutPrefix() {
        String instruction = talent(0).getInstruction(selecting("m/a"));

        assertTrue(instruction.contains("@m/a"));
        assertFalse(instruction.contains("name=\"@m/b\""));
    }

    @Test
    void unknownSelectionYieldsNoTalentAtAllInsteadOfFallingBackToAll() {
        SkillTalent talent = talent(0);
        Prompt prompt = selecting("@m/missing");

        // 空视图不得回退全量：否则"只勾了一个不存在的技能"会静默变成"全部可用"
        assertNull(talent.getInstruction(prompt));
        assertNull(talent.getTools(prompt));
    }

    @Test
    void selectedSkillIsPreloadedAndNoLongerNeedsSkillread() {
        String instruction = talent(12000).getInstruction(selecting("@m/a"));

        assertTrue(instruction.contains("<preloaded_skill name=\"@m/a\">"));
        assertTrue(instruction.contains("A 的正文"));
        assertTrue(instruction.contains("无需再次 skillread"));
    }

    @Test
    void preloadRespectsBudgetAndDegradesToNameOnly() {
        String instruction = talent(1).getInstruction(selecting("@m/a"));

        assertTrue(instruction.contains("skipped=\"over_budget\""));
        assertFalse(instruction.contains("A 的正文"));
    }

    @Test
    void preloadCanBeDisabled() {
        String instruction = talent(0).getInstruction(selecting("@m/a"));

        assertFalse(instruction.contains("<preloaded_skill"));
    }

    // ---------- 测试替身 ----------

    private static class FakeCatalog implements SkillCatalog {
        private final Map<String, SkillDescriptor> skills = new LinkedHashMap<>();
        private final Map<String, SkillContent> contents = new LinkedHashMap<>();

        FakeCatalog() {
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
            return Collections.emptyList();
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
