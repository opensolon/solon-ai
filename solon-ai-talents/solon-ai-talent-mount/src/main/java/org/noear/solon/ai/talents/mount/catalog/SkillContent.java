package org.noear.solon.ai.talents.mount.catalog;

import java.util.Objects;

/** 已读取的技能正文及兼容现有工具输出的展示文本。 */
public final class SkillContent {
    private final SkillDescriptor descriptor;
    private final String text;
    private final String renderedText;

    public SkillContent(SkillDescriptor descriptor, String text, String renderedText) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.text = Objects.requireNonNull(text, "text");
        this.renderedText = Objects.requireNonNull(renderedText, "renderedText");
    }

    public SkillDescriptor getDescriptor() { return descriptor; }
    public String getText() { return text; }
    public String getRenderedText() { return renderedText; }
}
