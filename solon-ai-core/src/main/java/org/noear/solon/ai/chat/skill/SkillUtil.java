/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.chat.skill;

import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.core.util.RankEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author noear
 * @since 3.8.4
 */
public class SkillUtil {
    private static final Logger LOG = LoggerFactory.getLogger(SkillUtil.class);

    /**
     * 激活技能
     */
    public static StringBuilder activeSkills(ModelOptionsAmend<?,?> modelOptions, Prompt prompt) {
        StringBuilder combinedInstruction = new StringBuilder();

        for (RankEntity<Skill> item : modelOptions.skills()) {
            Skill skill = item.target;

            try {
                if (skill.isSupported(prompt) == false) {
                    //不支持？跳过
                    continue;
                }
            } catch (Throwable e) {
                //出错？跳过
                LOG.error("Skill support check failed: {}", skill.getClass().getName(), e);
                continue;
            }

            try {
                // 挂载
                skill.onAttach(prompt);
            } catch (Throwable e) {
                LOG.error("Skill active failed: {}", skill.getClass().getName(), e);
                throw e;
            }

            //聚合提示词
            skill.injectInstruction(prompt, combinedInstruction);

            //部署工具
            modelOptions.toolAdd(skill.getTools());
        }

        return combinedInstruction;
    }
}