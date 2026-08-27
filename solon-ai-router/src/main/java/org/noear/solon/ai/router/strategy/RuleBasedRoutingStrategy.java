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
package org.noear.solon.ai.router.strategy;

import org.noear.solon.ai.router.ChatModelRoute;
import org.noear.solon.ai.router.RoutingContext;
import org.noear.solon.ai.router.RoutingDecision;
import org.noear.solon.ai.router.RoutingException;
import org.noear.solon.ai.router.RoutingRule;
import org.noear.solon.ai.router.RoutingStrategy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 有序规则路由策略
 *
 * @author bai
 * @since 4.1
 */
public final class RuleBasedRoutingStrategy implements RoutingStrategy {
    private final List<RoutingRule> rules;

    /**
     * @param rules 有序路由规则
     */
    public RuleBasedRoutingStrategy(Collection<RoutingRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new RoutingException("The routing rules are required");
        }

        List<RoutingRule> ruleList = new ArrayList<>(rules.size());
        for (RoutingRule rule : rules) {
            if (rule == null) {
                throw new RoutingException("The routing rule is required");
            }
            ruleList.add(rule);
        }

        this.rules = Collections.unmodifiableList(ruleList);
    }

    /**
     * 获取只读规则列表
     */
    public List<RoutingRule> getRules() {
        return rules;
    }

    /**
     * 按注册顺序选择首个匹配规则
     */
    @Override
    public RoutingDecision select(RoutingContext context, List<ChatModelRoute> routes) {
        for (RoutingRule rule : rules) {
            try {
                if (rule.getPredicate().test(context)) {
                    return new RoutingDecision(rule.getRouteId(),
                            "matched rule: " + rule.getRouteId());
                }
            } catch (RuntimeException e) {
                throw new RoutingException(
                        "Routing rule failed: " + rule.getRouteId(), e);
            }
        }

        throw new RoutingException("No routing rule matched");
    }
}
