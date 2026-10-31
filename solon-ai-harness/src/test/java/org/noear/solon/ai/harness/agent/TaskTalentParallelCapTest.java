/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * multitask 并行上限契约测试
 *
 * <p>锁定四个点：</p>
 * <ol>
 *   <li>{@code resolveConcurrency}：模型配置优先，未设置（0）时回退默认值。</li>
 *   <li>{@code isSerialOnly}：串行快路径的条件是「所有任务生效上限都为 1」，
 *       而不是「任一为 1」——后者会让一个 cap=1 的子代理把整批拖成串行。</li>
 *   <li>默认并发上限恒为有限正整数（0 = 未设置 → 缺省 5，上界 10），不存在「不限制」。</li>
 *   <li>{@code AiConfig#concurrency}：int + 0=未设置，负数报错。</li>
 * </ol>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentParallelCapTest {

    // ---------- resolveConcurrency：模型优先、默认回退 ----------

    @Test
    public void modelUnsetFallsBackToDefault() {
        assertEquals(5, TaskTalent.resolveConcurrency(0, 5));
    }

    @Test
    public void modelConfigWinsOverDefault() {
        // 关键回归：模型配了 8 就必须跑 8，不能被默认值 5 卡住（旧"两级取小"的缺陷）
        assertEquals(8, TaskTalent.resolveConcurrency(8, 5));
        assertEquals(3, TaskTalent.resolveConcurrency(3, 5));
    }

    @Test
    public void modelConfigWinsEvenWhenLowerThanDefault() {
        assertEquals(2, TaskTalent.resolveConcurrency(2, 10));
    }

    @Test
    public void defaultZeroFallsBackToBuiltInDefault() {
        assertEquals(TaskTalent.DEFAULT_CONCURRENCY, TaskTalent.resolveConcurrency(0, 0));
    }

    @Test
    public void resolveConcurrencyNeverReturnsUnlimited() {
        // 契约：不管怎么传 0，生效上限恒为正整数
        assertTrue(TaskTalent.resolveConcurrency(0, 0) > 0);
        assertTrue(TaskTalent.resolveConcurrency(0, TaskTalent.DEFAULT_CONCURRENCY) > 0);
        assertTrue(TaskTalent.DEFAULT_CONCURRENCY <= TaskTalent.MAX_CONCURRENCY);
    }

    @Test
    public void negativeDefaultFallsBackToBuiltInDefault() {
        // 防御分支：配置侧已拦截负数，纯函数也不把负数当上限
        assertEquals(TaskTalent.DEFAULT_CONCURRENCY, TaskTalent.resolveConcurrency(0, -1));
    }

    @Test
    public void setterNormalizesToBoundedRange() {
        TaskTalent t = new TaskTalent(null);

        t.setDefaultConcurrency(0);
        assertEquals(TaskTalent.DEFAULT_CONCURRENCY, t.getDefaultConcurrency(), "0 = 未设置，取缺省值");

        t.setDefaultConcurrency(TaskTalent.MAX_CONCURRENCY + 1);
        assertEquals(TaskTalent.MAX_CONCURRENCY, t.getDefaultConcurrency(), "超上界靠上界");

        t.setDefaultConcurrency(3);
        assertEquals(3, t.getDefaultConcurrency(), "区间内原样保留");

        assertThrows(IllegalArgumentException.class, () -> t.setDefaultConcurrency(-1));
        assertEquals(3, t.getDefaultConcurrency(), "抛异常不得改动已有值");
    }

    // ---------- isSerialOnly ----------

    @Test
    public void allModelsCapOneIsSerial() {
        assertTrue(TaskTalent.isSerialOnly(plans(1, 1, 1)));
    }

    @Test
    public void oneModelCapOneDoesNotForceSerial() {
        // 核心语义：混合模型时，一个 cap=1 的子代理不能把整批拖成串行
        assertFalse(TaskTalent.isSerialOnly(plans(1, 4)));
        assertFalse(TaskTalent.isSerialOnly(plans(4, 1)));
    }

    @Test
    public void noSlotModelsAreNotSerial() {
        // 无槽位（未知子代理/模型不可解析）不能断定为串行
        assertFalse(TaskTalent.isSerialOnly(plans(0, 0)));
    }

    @Test
    public void defaultOneMakesAllUnsetModelsSerial() {
        // 关键回归：模型未配置 + 默认值 1 时生效上限恒为 1，必须判为串行。
        // 若直接用裸 getConcurrency()（=0）判定，会错走并行路径
        assertTrue(TaskTalent.isSerialOnly(plans(
                TaskTalent.resolveConcurrency(0, 1), TaskTalent.resolveConcurrency(0, 1))));
    }

    @Test
    public void emptyBatchIsNotSerial() {
        assertFalse(TaskTalent.isSerialOnly(Collections.<TaskTalent.TaskPlan>emptyList()));
    }

    private static List<TaskTalent.TaskPlan> plans(Integer... caps) {
        List<TaskTalent.TaskPlan> plans = new ArrayList<>(caps.length);

        for (Integer cap : caps) {
            TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
            op.agent_name = "general";
            plans.add(new TaskTalent.TaskPlan(op, null, null, null, cap, null));
        }

        return plans;
    }

    // ---------- ChatConfig 字段契约 ----------

    @Test
    public void chatConfigMaxConcurrencyDefaultsToZero() {
        // int + 0=未设置，与 AiConfig#contextLength 同义
        assertEquals(0, new ChatConfig().getConcurrency());
    }

    @Test
    public void chatConfigRejectsNegative() {
        ChatConfig config = new ChatConfig();
        config.setConcurrency(3);
        assertEquals(3, config.getConcurrency());

        // 配错直接炸出来而不是被静默吞掉
        assertThrows(IllegalArgumentException.class, () -> config.setConcurrency(-1));
        assertEquals(3, config.getConcurrency(), "抛异常后原值不变");

        config.setConcurrency(0);
        assertEquals(0, config.getConcurrency(), "0 合法，表示未设置");
    }

    @Test
    public void chatConfigReadonlyExposesConcurrency() {
        ChatConfig config = new ChatConfig();
        config.setConcurrency(2);

        assertEquals(2, new org.noear.solon.ai.chat.ChatConfigReadonly(config).getConcurrency());
    }

    @Test
    public void chatConfigToStringContainsConcurrency() {
        ChatConfig config = new ChatConfig();
        config.setConcurrency(6);
        assertTrue(config.toString().contains("concurrency=6"), config.toString());
    }
}
