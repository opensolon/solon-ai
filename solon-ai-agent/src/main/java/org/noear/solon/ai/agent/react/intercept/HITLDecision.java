/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.Map;

/**
 * HITL 审批决策实体
 *
 * <p>该实体定义了人工对 AI 行为的最终裁决。支持以下行为：</p>
 * <ul>
 * <li><b>APPROVE</b>: 批准执行。支持通过 {@code modifiedArgs} 实现“指令下达，参数修正”。</li>
 * <li><b>REJECT</b>: 强制终止。流程直接路由至 END，Agent 会输出拒绝理由。</li>
 * <li><b>SKIP</b>: 逻辑跳过。不执行真实工具，但向 Agent 返回一条 Observation 告知跳过原因。</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITLDecision implements Serializable {
    public static final int ACTION_APPROVE = 1;
    public static final int ACTION_REJECT = 2;
    public static final int ACTION_SKIP = 3; // 新增：跳过

    /**
     * 是否批准执行
     */
    private int action;
    /**
     * 审批意见（拒绝理由或操作备注）
     */
    private String comment;
    /**
     * 修正后的参数（若不为空，将覆盖 AI 生成的原参数）
     */
    private Map<String, Object> modifiedArgs;

    public HITLDecision() {
        //用于反序列化
    }

    /**
     * 快速创建批准决策
     */
    public static HITLDecision approve() {
        return new HITLDecision().action(ACTION_APPROVE);
    }

    /**
     * 快速创建拒绝决策
     */
    public static HITLDecision reject(String comment) {
        return new HITLDecision().action(ACTION_REJECT).comment(comment);
    }

    /**
     * 快速创建跳过决策
     */
    public static HITLDecision skip(String comment) {
        return new HITLDecision().action(ACTION_SKIP).comment(comment);
    }

    protected HITLDecision action(int action) {
        this.action = action;
        return this;
    }


    public HITLDecision comment(String comment) {
        this.comment = comment;
        return this;
    }

    public HITLDecision modifiedArgs(Map<String, Object> modifiedArgs) {
        this.modifiedArgs = modifiedArgs;
        return this;
    }

    public boolean isApproved() {
        return action == ACTION_APPROVE;
    }

    public boolean isRejected() {
        return action == ACTION_REJECT;
    }

    public boolean isSkipped() {
        return action == ACTION_SKIP;
    }

    public String getComment() {
        return comment;
    }

    /**
     * 获取备注信息，若为空则返回默认拒绝文案
     */
    public String getCommentOrDefault(String def) {
        if (Assert.isEmpty(comment)) {
            return def;
        } else {
            return comment;
        }
    }

    public Map<String, Object> getModifiedArgs() {
        return modifiedArgs;
    }
}