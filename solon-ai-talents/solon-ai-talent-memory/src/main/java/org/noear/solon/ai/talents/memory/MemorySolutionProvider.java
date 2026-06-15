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
package org.noear.solon.ai.talents.memory;

/**
 * 记忆方案提供者
 *
 * 负责根据运行上下文（如工作目录、租户标识等）构建或检索对应的记忆方案。
 *
 * @author noear
 * @since 4.0.0
 */
public interface MemorySolutionProvider {
    /**
     * 根据当前上下文标识获取记忆方案
     *
     * @param __cwd 当前工作区
     * @return 匹配的记忆方案实例
     */
    MemorySolution get(String __cwd);
}
