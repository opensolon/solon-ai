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
package org.noear.solon.ai.talents.mount.catalog;

import org.noear.solon.lang.Preview;

import java.util.Objects;

/** 
 * 已读取的技能正文及兼容现有工具输出的展示文本。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class SkillContent {
    private final SkillDescriptor descriptor;
    private final String text;
    private final String renderedText;

    /**
     * 创建技能正文内容。
     *
     * @param descriptor 技能描述信息
     * @param text 技能原始正文
     * @param renderedText 兼容工具输出的展示文本
     */
    public SkillContent(SkillDescriptor descriptor, String text, String renderedText) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.text = Objects.requireNonNull(text, "text");
        this.renderedText = Objects.requireNonNull(renderedText, "renderedText");
    }

    /**
     * 获取技能描述信息。
     *
     * @return 技能描述信息
     */
    public SkillDescriptor getDescriptor() { return descriptor; }

    /**
     * 获取技能原始正文。
     *
     * @return 技能原始正文
     */
    public String getText() { return text; }

    /**
     * 获取兼容工具输出的展示文本。
     *
     * @return 展示文本
     */
    public String getRenderedText() { return renderedText; }
}
