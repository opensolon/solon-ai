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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * multitask 并行上限契约测试
 *
 * <p>锁定三个点：</p>
 * <ol>
 *   <li>{@code resolveConcurrency}：模型配置优先，未设置（0）时回退默认值。
 *       不存在"两级取小"那种"配了 8 却只跑 5"的静默降级。</li>
 *   <li>{@code isSerialOnlyByCaps}：串行快路径的条件是"所有任务生效上限都为 1"，
 *       而不是"任一为 1"——后者会让一个 cap=1 的子代理把整批拖成串行。</li>
 *   <li>{@code AiConfig#concurrency}：int + 0=未设置，getter 恒非 null，负数报错。</li>
 * </ol>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentParallelCapTest {

    // ---------- resolveConcurrency：模型优先、默认回退 ----------

    @Test
    public void modelUnsetFallsBackToDefault() {
        // 模型未配置（0 = 未设置）→ 用默认值
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
        // 模型配得更低时也以模型为准，这是"按模型配额限流"的初衷
        assertEquals(2, TaskTalent.resolveConcurrency(2, 10));
    }

    @Test
    public void defaultZeroMeansUnlimitedWhenModelUnset() {
        // 默认值为 0 = 整体放开：模型也没配时就是不限
        assertEquals(0, TaskTalent.resolveConcurrency(0, 0));
    }

    @Test
    public void modelUnlimitedCannotBeExpressedInTwoStateContract() {
        // 已接受的表达力损失：0 被"未设置"占用，模型级无法单独表达"不限"，
        // 此时只能靠把默认值设为 0 整体放开
        assertEquals(5, TaskTalent.resolveConcurrency(0, 5));
    }

    @Test
    public void negativeDefaultTreatedAsUnlimited() {
        // 防御分支：配置侧已拦截负数，这里不会把负数当上限
        assertEquals(0, TaskTalent.resolveConcurrency(0, -1));
    }

    // ---------- isSerialOnlyByCaps ----------

    @Test
    public void allModelsCapOneIsSerial() {
        // 所有任务的目标模型生效上限都是 1：走调用线程串行快路径
        assertTrue(TaskTalent.isSerialOnlyByCaps(caps(1, 1, 1)));
    }

    @Test
    public void oneModelCapOneDoesNotForceSerial() {
        // 核心语义：混合模型时，一个 cap=1 的子代理不能把整批拖成串行。
        // 若这里判成 true，可 4 并发的那个会被连累，回到"一刀切"缺陷
        assertFalse(TaskTalent.isSerialOnlyByCaps(caps(1, 4)));
        assertFalse(TaskTalent.isSerialOnlyByCaps(caps(4, 1)));
    }

    @Test
    public void unlimitedModelsAreNotSerial() {
        assertFalse(TaskTalent.isSerialOnlyByCaps(caps(0, 0)));
    }

    @Test
    public void defaultOneMakesAllUnsetModelsSerial() {
        // 关键回归：模型未配置 + 默认值 1 时，生效上限恒为 1，必须判为串行。
        // 若直接用裸 getConcurrency()（=0）判定，会错走并行路径
        int DEFAULT = 1;
        assertTrue(TaskTalent.isSerialOnlyByCaps(
                caps(TaskTalent.resolveConcurrency(0, DEFAULT), TaskTalent.resolveConcurrency(0, DEFAULT))));
    }

    @Test
    public void unresolvableModelIsNotSerial() {
        // 模型全禁用时不能断定串行，交给常规路径的"未知子代理"分支处理
        assertFalse(TaskTalent.isSerialOnlyByCaps(Collections.<Integer>singletonList(null)));
    }

    @Test
    public void noResolvedModelIsNotSerial() {
        // 所有 task 都是未知子代理：没有可判定对象，走常规路径返回错误文案
        assertFalse(TaskTalent.isSerialOnlyByCaps(Collections.<Integer>emptyList()));
    }

    private static List<Integer> caps(Integer... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    // ---------- ChatConfig 字段契约 ----------

    @Test
    public void chatConfigMaxConcurrencyDefaultsToZero() {
        // int + 0=未设置，与 AiConfig#contextLength 的 0=未设置 同义；getter 永不返回 null
        assertEquals(0, new ChatConfig().getConcurrency());
    }

    @Test
    public void chatConfigRejectsNegative() {
        ChatConfig config = new ChatConfig();
        config.setConcurrency(3);
        assertEquals(3, config.getConcurrency());

        // int 契约：没有 null 可言，配错直接炸出来而不是被静默吞掉
        assertThrows(IllegalArgumentException.class, () -> config.setConcurrency(-1));

        assertEquals(3, config.getConcurrency(), "抛异常后原值不变");

        config.setConcurrency(0);
        assertEquals(0, config.getConcurrency(), "0 合法，表示未设置");
    }

    @Test
    public void chatConfigReadonlyExposesConcurrency() {
        ChatConfig config = new ChatConfig();
        config.setConcurrency(2);

        // 不构建 ChatModel（会校验 apiUrl），直接验证只读出口透传
        assertEquals(2, new org.noear.solon.ai.chat.ChatConfigReadonly(config).getConcurrency());
    }

    @Test
    public void chatConfigToStringContainsConcurrency() {
        ChatConfig config = new ChatConfig();
        config.setConcurrency(6);
        assertTrue(config.toString().contains("concurrency=6"), config.toString());
    }
}
