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
package org.noear.solon.ai.mount;

import org.noear.solon.lang.Preview;

/**
 * 挂载变更监听器：挂载注册（含同别名替换）或移除后回调。
 *
 * <p>{@link MountManager} 只负责挂载表与路径解析，不认识技能、子代理等上层目录；
 * 上层目录（如 {@code DefaultSkillCatalog}）通过本接口订阅变更并自行刷新。</p>
 *
 * <p>回调契约：在挂载表更新完成、且管理器锁释放之后同步调用（回调内可以安全地读取挂载表）；
 * 单个监听器抛出的异常只记录日志，不影响挂载操作本身，也不影响其他监听器。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public interface MountListener {
    /**
     * 挂载已注册（同别名时为替换后的新挂载）。
     *
     * @param mount 别名规范化后的挂载
     */
    default void onMountRegistered(Mount mount) {
    }

    /**
     * 挂载已移除。
     *
     * @param mount 被移除的挂载
     */
    default void onMountRemoved(Mount mount) {
    }
}
