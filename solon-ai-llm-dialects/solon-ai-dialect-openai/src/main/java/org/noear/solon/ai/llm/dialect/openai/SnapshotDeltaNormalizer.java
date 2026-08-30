/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.solon.Utils;

/**
 * 单通道（正文 / 思考）的「累计快照 → 流式增量」归一器。
 *
 * <p>官方协议的 delta 是新增文本；少数 OpenAI 兼容网关会依次下发 "a"、"ab"、"abc"（累计快照），
 * 核心层无条件追加就会得到成倍膨胀的文本。这里按原始报文自行累积，并只在证据足够时才把快照截为后缀：</p>
 * <ul>
 *   <li>累积长度达到 {@link #MIN_MATCH_LENGTH} 才允许首次判定，避免 "好"/"好的"、"\n"/"\n" 这类
 *       短增量被误判（普通增量在流首极易偶然形成前缀关系）；</li>
 *   <li>首次判定还要求本帧严格长于累积（真的有新增后缀），不接受完全相等；</li>
 *   <li>一旦判定为快照流即进入粘滞模式（{@link #isSnapshotMode()}），此后才允许把完全相等的重复帧吃掉。</li>
 * </ul>
 *
 * <p>累积基准是本类自己 append 的报文文本，不受 think 标签分流、多 TextBlock 拼接等核心层加工影响。</p>
 *
 * @author noear
 * @since 4.1
 */
class SnapshotDeltaNormalizer {
    /**
     * 首次判定所需的最小累积长度（低于该长度一律按普通增量处理）
     */
    static final int MIN_MATCH_LENGTH = 8;

    private final StringBuilder accumulated = new StringBuilder();
    private boolean snapshotMode;

    /**
     * 是否已判定为累计快照流
     */
    boolean isSnapshotMode() {
        return snapshotMode;
    }

    /**
     * 当前累积文本长度
     */
    int length() {
        return accumulated.length();
    }

    /**
     * 归一化一帧：返回本帧真正的新增文本（快照重复帧返回空串），并把新增部分记入累积。
     */
    String normalize(String value) {
        if (Utils.isEmpty(value)) {
            return value;
        }

        String delta = value;
        int len = accumulated.length();

        if (len > 0) {
            if (snapshotMode) {
                if (startsWithAccumulated(value)) {
                    delta = value.substring(len); //含完全相等 → ""
                }
            } else if (len >= MIN_MATCH_LENGTH && value.length() > len && startsWithAccumulated(value)) {
                snapshotMode = true;
                delta = value.substring(len);
            }
        }

        accumulated.append(delta);
        return delta;
    }

    /**
     * 不做判定地直接记入累积（用于官方独有事件：不存在快照实现，无需承担误判风险，
     * 但仍要参与后续通道的累积基准）。
     */
    void append(String value) {
        if (Utils.isNotEmpty(value)) {
            accumulated.append(value);
        }
    }

    /**
     * 逐字符前缀比较，省去每帧 {@code accumulated.toString()} 的拷贝
     */
    private boolean startsWithAccumulated(String value) {
        int len = accumulated.length();
        if (value.length() < len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (value.charAt(i) != accumulated.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
