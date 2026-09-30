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
package org.noear.solon.ai.talents.mount.source;

import org.noear.solon.lang.Preview;

/** 
 * 文件移动选项。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class MoveOptions {
    private final boolean replaceExisting;

    /**
     * 创建文件移动选项。
     *
     * @param replaceExisting 是否替换已有目标
     */
    private MoveOptions(boolean replaceExisting) {
        this.replaceExisting = replaceExisting;
    }

    /** @return 是否替换已有目标 */
    public boolean isReplaceExisting() { return replaceExisting; }
    /** @return 不替换已有目标的默认移动选项 */
    public static MoveOptions defaults() { return new MoveOptions(false); }
    /** @return 替换已有目标的移动选项 */
    public static MoveOptions replaceExisting() { return new MoveOptions(true); }
}
