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
 * <p>用于承载人工干预的结果，支持批准、拒绝以及对 AI 拟调用参数的修正</p>
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