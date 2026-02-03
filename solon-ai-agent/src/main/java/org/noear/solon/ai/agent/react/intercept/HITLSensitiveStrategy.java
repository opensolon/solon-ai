package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.core.util.Assert;

import java.util.Map;

/**
 *
 * @author noear 2026/2/3 created
 *
 */
public class HITLSensitiveStrategy implements HITLInterceptor.InterventionStrategy {
    private String comment;

    public HITLSensitiveStrategy comment(String comment) {
        this.comment = comment;
        return this;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        if (Assert.isEmpty(comment)) {
            return "敏感操作，需要人工介入确认";
        } else {
            return comment;
        }
    }
}