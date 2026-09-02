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
package org.noear.solon.ai.llm.dialect.dashscope;

import org.noear.solon.Utils;

/**
 * 单通道（正文 / 思考）的「累计快照 → 流式增量」归一器。
 *
 * <p>判定规则与 openai 方言的同名类逐字一致（该类是包级私有、且方言各自独立成模块，
 * 无法跨模块引用，因此这里做同规则镜像；改动请两边同步）。为什么 DashScope 也需要它：
 * 原生 DashScope 协议的 {@code parameters.incremental_output} 缺省为 false，
 * 服务端每帧返回「从头开始的全量快照」，而核心累积器按协议只接受增量（逐帧追加），
 * 两者叠加会让正文与 reasoning_content 逐帧重复累加。请求侧已显式开启增量输出
 * （见 {@code DashscopeChatDialect#buildRequestJson}），本类只作为兜底：
 * 用户显式把 incremental_output 设为 false、或中转端点忽略该参数时仍能自愈。</p>
 *
 * <p>只在证据足够时才把快照截为后缀：</p>
 * <ul>
 *   <li>累积长度达到 {@link #MIN_MATCH_LENGTH} 才允许首次判定，避免 "好"/"好的"、"\n"/"\n" 这类
 *       短增量被误判（普通增量在流首极易偶然形成前缀关系）；</li>
 *   <li>首次判定还要求本帧严格长于累积（真的有新增后缀），不接受完全相等；</li>
 *   <li>一旦判定为快照流即进入粘滞模式（{@link #isSnapshotMode()}），此后才允许把完全相等的重复帧吃掉。</li>
 * </ul>
 *
 * <p>代价是：真快照流的前 {@link #MIN_MATCH_LENGTH} 个字符仍会被追加一次（判定期无法区分），
 * 这是「宁可少截、不可错截」的取舍——错截会永久吞掉正文，比一次性重复更难排查。</p>
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
     * 不做判定地直接记入累积（用于不存在快照实现的字段：无需承担误判风险，
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
