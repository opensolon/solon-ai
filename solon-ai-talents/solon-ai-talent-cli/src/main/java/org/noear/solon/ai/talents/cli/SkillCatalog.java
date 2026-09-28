/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.noear.solon.ai.talents.cli;

import java.util.Collection;

/** 技能目录：负责技能索引、搜索、读取和刷新。 */
public interface SkillCatalog {
    void refresh();

    default void refreshByMount(String mountAlias) {
        refresh();
    }

    int getSkillCount();

    Collection<SkillDescriptor> getDescriptors();

    Collection<SkillDescriptor> searchDescriptors(String query);

    SkillDescriptor getDescriptor(String name);

    SkillContent readContent(String name);

    boolean isAllowed(SkillDescriptor descriptor);
}
