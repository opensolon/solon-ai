/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.input;

import org.noear.solon.lang.Preview;

/**
 * 会话忙（正在运行 / 受理窗口 / 有积压）时怎么处理这条输入（{@link HarnessInput#whenBusy(BusyPolicy)}）。
 *
 * <p>忙时可执行的命令、待审批的会话不受影响：前者照常执行，后者由引擎给出 {@code HITL_PENDING}。
 * 判定在引擎受理的会话锁内完成，与「是否忙」同一临界区，不存在先判空闲、再被别的输入抢先的窗口。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public enum BusyPolicy {
    /** 进积压，当前一轮结束后自动续发（缺省；推送型宿主用）。没有装积压插件时按 {@code BUSY} 拒绝 */
    QUEUE,

    /**
     * 先作为插话交给正在运行的那一轮，插不进再排队（IM / 聊天输入框「边跑边补充」用）。
     *
     * <p>插话由宿主骨架（{@code HarnessHost}）在受理前尝试，需装配 steer 插件；斜杠命令、带附件的输入不插话。
     * 直接调 {@code engine.handle} 时按 {@link #QUEUE} 处理。</p>
     */
    STEER,

    /**
     * 直接拒绝（{@code BUSY}），不进积压（一问一答型宿主用：请求结束了，排队的那一轮没人接）。
     * {@link HarnessInput#ofProgram} 创建的程序轮次总是按此处理。
     */
    REJECT
}
