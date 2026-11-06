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
package org.noear.solon.ai.harness.util;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * harness 统一 ID 与路径段工具。
 *
 * <p><b>为什么不截断</b>：截取 UUID 前 8 位只有 32 bit 熵，十万级任务时生日碰撞概率约 69%；
 * 一旦落到共享存储（db / 服务端）或共享 JobManager（JVM 单例、同名静默沿用旧 job），撞号就是数据串号。
 * 所有需要持久化、跨进程或对外暴露的 ID 一律使用 {@link #newId}（完整 128 bit）。</p>
 *
 * <p>格式：{@code prefix + 32 位小写 hex}（不带连字符，便于做文件名、job 名与 URL 段）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class HarnessIds {
    /** 允许作为单个存储路径段的字符（会话 ID、任务 ID 等外部输入）。 */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._@:\\-]{1,128}");

    private HarnessIds() {
    }

    /**
     * 生成完整 ID（无前缀）。
     */
    public static String newId() {
        return newId(null);
    }

    /**
     * 生成完整 ID。
     *
     * @param prefix 前缀（可空），如 {@code "q_"}
     */
    public static String newId(String prefix) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return prefix == null ? hex : prefix + hex;
    }

    /**
     * 是否可安全地用作单个存储路径段（不含路径分隔符、不是 {@code .}/{@code ..}、长度 1~128）。
     */
    public static boolean isSafeSegment(String value) {
        return value != null
                && SAFE_SEGMENT.matcher(value).matches()
                && !".".equals(value)
                && !"..".equals(value);
    }

    /**
     * 校验并返回安全路径段。
     *
     * @throws IllegalArgumentException 不安全（可能造成目录穿越或越界）
     */
    public static String requireSafeSegment(String value, String name) {
        if (!isSafeSegment(value)) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return value;
    }
}
