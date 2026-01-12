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
package org.noear.solon.ai.agent.team.intercept;

import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 协作死循环拦截器
 * * <p>通过回溯 TeamTrace 识别并阻断 AI 节点间的无效重复。支持：</p>
 * <ul>
 * <li>1. <b>单点停滞 (Self-Loop)</b>：同一 Agent 连续产出高度相似的内容。</li>
 * <li>2. <b>序列嵌套 (Pattern-Loop)</b>：多 Agent 间形成固定序列的“踢皮球”（如 A-B-A-B）。</li>
 * </ul>
 */
@Preview("3.8.1")
public class LoopingTeamInterceptor implements TeamInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(LoopingTeamInterceptor.class);

    /** 最小检测长度（过滤 "OK", "Done" 等短语干扰） */
    private int minContentLength = 10;

    /** 语义相似度阈值 (0.0 ~ 1.0) */
    private double similarityThreshold = 0.95;

    /** 回溯检测的最大步数窗口 */
    private int scanWindowSize = 10;

    /** 允许的最大重复次数 */
    private int maxRepeatAllowed = 0;

    @Override
    public boolean shouldSupervisorContinue(TeamTrace trace) {
        if (isLooping(trace)) {
            LOG.warn("Team loop detected! Terminating flow to prevent resource exhaustion.");
            return false;
        }
        return true;
    }

    /**
     * 多策略循环检测逻辑
     */
    public boolean isLooping(TeamTrace trace) {
        List<TeamTrace.TeamStep> allSteps = trace.getSteps();

        // 仅针对 Agent 产出进行审计，排除系统开销步骤
        List<TeamTrace.TeamStep> agentSteps = allSteps.stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .collect(Collectors.toList());

        int n = agentSteps.size();
        // 步数不足以判定循环（至少需要 A-B-A-B 四步）
        if (n < 4) return false;

        TeamTrace.TeamStep lastStep = agentSteps.get(n - 1);

        if (lastStep.getContent() == null || lastStep.getContent().length() < minContentLength) {
            return false;
        }

        // 策略 A：检测 Agent 自我复读
        if (checkSelfLoop(agentSteps, n)) {
            LOG.debug("Self-loop detected on agent: {}", lastStep.getSource());
            return true;
        }

        // 策略 B：检测多步协同死锁 (A-B-A-B 或 A-B-C-A-B-C)
        if (checkSequenceLoop(agentSteps, n)) {
            LOG.debug("Sequence-loop detected ending with: {}", lastStep.getSource());
            return true;
        }

        return false;
    }

    /**
     * 检测同一 Agent 是否在原地踏步
     */
    private boolean checkSelfLoop(List<TeamTrace.TeamStep> steps, int n) {
        TeamTrace.TeamStep last = steps.get(n - 1);
        int repeatCount = 0;
        int limit = Math.max(0, n - scanWindowSize);

        for (int i = n - 2; i >= limit; i--) {
            TeamTrace.TeamStep prev = steps.get(i);
            if (prev.getSource().equals(last.getSource())) {
                if (calculateSimilarity(prev.getContent(), last.getContent()) >= similarityThreshold) {
                    repeatCount++;
                    if (repeatCount > maxRepeatAllowed) return true;
                } else {
                    // 内容发生演进，单点循环打破
                    break;
                }
            }
        }
        return false;
    }

    /**
     * 检测协作序列是否进入循环节
     */
    private boolean checkSequenceLoop(List<TeamTrace.TeamStep> steps, int n) {
        // len 为检测的循环节长度（2=AB型, 3=ABC型）
        for (int len = 2; len <= 3; len++) {
            if (n < len * 2) continue;

            boolean match = true;
            for (int i = 0; i < len; i++) {
                TeamTrace.TeamStep current = steps.get(n - 1 - i);
                TeamTrace.TeamStep previous = steps.get(n - 1 - i - len);

                // 节点名与内容需双重匹配
                if (!current.getSource().equals(previous.getSource()) ||
                        calculateSimilarity(current.getContent(), previous.getContent()) < similarityThreshold) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    /**
     * 基于归一化编辑距离的语义相似度计算
     */
    private double calculateSimilarity(String s1, String s2) {
        if (Objects.equals(s1, s2)) return 1.0;

        String n1 = normalize(s1);
        String n2 = normalize(s2);
        if (n1.equals(n2)) return 1.0;

        double maxLen = Math.max(n1.length(), n2.length());
        if (maxLen == 0) return 0.0;

        int distance = editDistance(n1, n2);
        return 1.0 - (double) distance / maxLen;
    }

    /**
     * Levenshtein Distance (编辑距离) 实现
     */
    private int editDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) costs[j] = j;
                else {
                    if (j > 0) {
                        int newValue = costs[j - 1];
                        if (s1.charAt(i - 1) != s2.charAt(j - 1))
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) costs[s2.length()] = lastValue;
        }
        return costs[s2.length()];
    }

    /**
     * 降噪处理：移除空白及格式干扰
     */
    private String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", "").toLowerCase();
    }
}