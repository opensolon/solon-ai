/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.spi;

import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 会话归属（多节点部署的单写者租约）
 *
 * <p>会话的运行态（流句柄、受理占位、插话邮箱、队列锁）都在本进程内存里，因此同一时刻只能由一个节点执行
 * 某个会话。引擎在受理（{@code handle} 第 0 步）前调用 {@link #tryAcquire}：拿不到时返回
 * {@code REJECTED} + {@code InputOutcome.REJECT_NOT_OWNER}，{@code getRejectDetail()} 为持有者节点标识，
 * 由网关转发；goal / loop 的程序轮次同样经过 {@code handle}，冷会话由先触发的节点自然认领、其余节点跳过。</p>
 *
 * <p>租约生命周期：从首次 {@link #tryAcquire} 成功起，到 {@link #release} 为止（引擎在
 * {@code removeSession} 与 {@code shutdown} 时释放，后者释放本引擎认领过的全部会话）。
 * <b>持有期间由实现自行保活</b>（如节点心跳批量续期、Redisson watchdog）：引擎只在受理时调用
 * {@link #tryAcquire}，一次运行可能远长于租期（goal 长轮次、慢工具），靠受理续约会在运行中过期、
 * 造成双写。租约只应在节点失联（心跳停止）后过期。</p>
 *
 * <p>实现要求：{@link #tryAcquire} 对已持有的会话返回 true；必须廉价（每次受理都会调用，建议本地缓存已持有集合）。
 * 典型实现为 Redis {@code SET key node NX PX lease} + 节点心跳对已持有集合续期。单机部署用 {@link #LOCAL}。</p>
 *
 * <p>不做分布式时也可以只在网关按 sessionId 一致性哈希做粘滞路由，但这是<b>部署前提</b>，需写进运维文档。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public interface SessionOwnership {
    /**
     * 申请或续约会话的执行权
     *
     * @return 本节点持有（或已续约）时为 true；已被其它存活节点持有时为 false
     */
    boolean tryAcquire(String sessionId);

    /**
     * 当前持有者节点标识（未知时为 null）
     */
    @Nullable
    String ownerOf(String sessionId);

    /**
     * 主动释放（会话删除、引擎关闭时；可能对未持有的会话调用，实现应忽略）
     */
    void release(String sessionId);

    /**
     * 单机：永远持有
     */
    SessionOwnership LOCAL = new SessionOwnership() {
        @Override
        public boolean tryAcquire(String sessionId) {
            return true;
        }

        @Override
        public String ownerOf(String sessionId) {
            return "local";
        }

        @Override
        public void release(String sessionId) {
        }

        @Override
        public String toString() {
            return "SessionOwnership.LOCAL";
        }
    };
}
