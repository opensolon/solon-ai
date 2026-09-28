package org.noear.solon.ai.talents.mount.catalog;

import java.util.Objects;

/** 来源无关的技能元数据；id 是可用于读取的唯一逻辑路径。 */
public final class SkillDescriptor {
    private final String id;
    private final String name;
    private final String description;
    private final String version;

    public SkillDescriptor(String id, String name, String description, String version) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.version = version == null ? "" : version;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
}
