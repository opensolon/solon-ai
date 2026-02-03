package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.core.util.Assert;

import java.io.Serializable;
import java.util.Map;

/**
 * HITL 审批决策实体
 */
public class HITLDecision implements Serializable {
    private boolean approved;        // 是否同意
    private String comment;         // 审批意见（拒绝理由或操作备注）
    private Map<String, Object> modifiedArgs; // 修正后的参数

    public HITLDecision() {
        //用于反序列化
    }

    public static HITLDecision approve() {
        return new HITLDecision().setApproved(true);
    }

    public static HITLDecision reject(String comment) {
        return new HITLDecision().setApproved(false).setComment(comment);
    }

    public boolean isApproved() { return approved; }
    public HITLDecision setApproved(boolean approved) { this.approved = approved; return this; }

    public String getComment() { return comment; }
    public String getCommentOrDefault() {
        if (Assert.isEmpty(comment)) {
            return "操作被拒绝：人工审批未通过。";
        } else {
            return comment;
        }
    }
    public HITLDecision setComment(String comment) { this.comment = comment; return this; }

    public Map<String, Object> getModifiedArgs() { return modifiedArgs; }
    public HITLDecision setModifiedArgs(Map<String, Object> modifiedArgs) { this.modifiedArgs = modifiedArgs; return this; }
}