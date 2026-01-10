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

import java.util.List;
import java.util.Objects;

/**
 * 智能协作死循环拦截器
 * <p>该拦截器通过分析团队协作足迹（TeamTrace），自动识别并阻断 AI 节点间的无效重复执行。</p>
 *
 * <p><b>核心监测维度：</b></p>
 * <ul>
 * <li>1. <b>单点停滞 (Self-Loop)</b>：同一 Agent 连续产出高度相似的内容。</li>
 * <li>2. <b>序列嵌套 (Pattern-Loop)</b>：多 Agent 间形成固定序列的“踢皮球”现象（如 A-B-A-B 或 A-B-C-A-B-C）。</li>
 * </ul>
 *
 * @author noear
 * @since 3.8.1
 */
@Preview("3.8.1")
public class LoopingTeamInterceptor implements TeamInterceptor {
    /** 最小内容长度，低于此值不判定为循环（避免误伤 "OK", "Done" 等短语） */
    private int minContentLength = 10;

    /** 判定为重复的语义相似度阈值 (0.0 ~ 1.0)，建议在 0.9 以上 */
    private double similarityThreshold = 0.95;

    /** 回溯检测的最大步数窗口，防止长对话下的性能损耗 */
    private int scanWindowSize = 10;

    /** 允许重复的最大次数（默认 0，即一旦发现重复立即拦截；设为 1 则允许一次重试自纠） */
    private int maxRepeatAllowed = 0;

    /**
     * 在监管员决策前执行拦截逻辑
     * @return true 继续决策；false 发现循环，强行终止流程
     */
    @Override
    public boolean shouldSupervisorContinue(TeamTrace trace) {
        return !isLooping(trace);
    }

    /**
     * 执行多策略循环检测
     */
    public boolean isLooping(TeamTrace trace) {
        int n = trace.getStepCount();
        // 协作步数不足以形成逻辑闭环（至少需要 4 步才能判定 A-B-A-B）
        if (n < 4) return false;

        List<TeamTrace.TeamStep> steps = trace.getSteps();
        TeamTrace.TeamStep lastStep = steps.get(n - 1);

        // 过滤无需检测的极短文本
        if (lastStep.getContent() == null || lastStep.getContent().length() < minContentLength) {
            return false;
        }

        // 策略 A：单点深度重复检测。处理 Agent 陷入自我复读的情况
        if (checkSelfLoop(steps, n)) return true;

        // 策略 B：多步序列重复检测。处理 Agent 之间形成 A-B-A-B 或 A-B-C-A-B-C 的死锁
        if (checkSequenceLoop(steps, n)) return true;

        return false;
    }

    /**
     * 检测单点 Agent 幂等性停滞
     */
    private boolean checkSelfLoop(List<TeamTrace.TeamStep> steps, int n) {
        TeamTrace.TeamStep last = steps.get(n - 1);
        int repeatCount = 0;
        int limit = Math.max(0, n - scanWindowSize);

        for (int i = n - 2; i >= limit; i--) {
            TeamTrace.TeamStep prev = steps.get(i);
            // 匹配同一 Agent 的历史产出
            if (prev.getAgentName().equals(last.getAgentName())) {
                if (calculateSimilarity(prev.getContent(), last.getContent()) >= similarityThreshold) {
                    repeatCount++;
                    if (repeatCount > maxRepeatAllowed) return true;
                } else {
                    // 只要中途产出了不同内容，视为逻辑演进，单点循环打破
                    break;
                }
            }
        }
        return false;
    }

    /**
     * 检测多步协同序列死循环
     * 识别模式：[A, B, A, B] (len=2) 或 [A, B, C, A, B, C] (len=3)
     */
    private boolean checkSequenceLoop(List<TeamTrace.TeamStep> steps, int n) {
        // len 代表循环节的长度
        for (int len = 2; len <= 3; len++) {
            if (n < len * 2) continue;

            boolean match = true;
            for (int i = 0; i < len; i++) {
                TeamTrace.TeamStep current = steps.get(n - 1 - i);
                TeamTrace.TeamStep previous = steps.get(n - 1 - i - len);

                // 必须 Agent 名称与内容相似度同时匹配才判定为序列循环
                if (!current.getAgentName().equals(previous.getAgentName()) ||
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
     * 计算两段文本的语义相似度
     * 基于归一化处理及编辑距离（Levenshtein Distance）实现
     */
    private double calculateSimilarity(String s1, String s2) {
        if (Objects.equals(s1, s2)) return 1.0;

        // 归一化处理：忽略空白字符、排版及大小写干扰
        String n1 = normalize(s1);
        String n2 = normalize(s2);
        if (n1.equals(n2)) return 1.0;

        double maxLen = Math.max(n1.length(), n2.length());
        if (maxLen == 0) return 0.0;

        // 计算差异权重：1.0 - (修改次数 / 最大长度)
        int distance = editDistance(n1, n2);
        return 1.0 - (double) distance / maxLen;
    }

    /**
     * 编辑距离算法实现 (Levenshtein Distance)
     * 用于评估将字符串 A 转换为 B 所需的最少操作次数（插入、删除、替换）
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
     * 内容除噪归一化
     */
    private String normalize(String text) {
        if (text == null) return "";
        // 移除换行、空格等不可见字符，统一小写
        return text.replaceAll("\\s+", "").toLowerCase();
    }
}