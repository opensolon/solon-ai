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
package org.noear.solon.ai.harness.session;

import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 会话归属检查结果（{@code HarnessEngine#checkOwner}）
 *
 * <p>多节点部署时，会话的运行态与写入只能发生在持有节点上。{@code handle} 之外的写门面
 * （queue / steer / goal / loop / interrupt）先做本检查：本节点持有（或首次认领成功）时 {@link #isOwned()} 为 true；
 * 已被其它节点持有时为 false，{@link #getOwner()} 是持有者节点标识，由网关转发。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class OwnerCheck {
    private static final OwnerCheck OWNED = new OwnerCheck(true, null);

    private final boolean owned;
    private final String owner;

    private OwnerCheck(boolean owned, String owner) {
        this.owned = owned;
        this.owner = owner;
    }

    /** 本节点持有 */
    public static OwnerCheck owned() {
        return OWNED;
    }

    /** 其它节点持有 */
    public static OwnerCheck notOwned(@Nullable String owner) {
        return new OwnerCheck(false, owner);
    }

    /** 本节点是否持有该会话 */
    public boolean isOwned() {
        return owned;
    }

    /** 持有者节点标识（仅 {@code isOwned()==false} 时有意义；未知为 null） */
    @Nullable
    public String getOwner() {
        return owner;
    }

    @Override
    public String toString() {
        return owned ? "OwnerCheck{owned}" : "OwnerCheck{notOwned, owner=" + owner + "}";
    }
}
