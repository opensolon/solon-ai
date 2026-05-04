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
package org.noear.solon.ai.ui.agui;

import java.util.List;

/**
 * AG-UI 运行结果，区分正常完成和中断暂停两种结局
 * <p>
 * 当 type 为 "success" 时表示正常完成；当 type 为 "interrupt" 时表示运行暂停等待用户输入。
 * 省略 outcome 字段等同于正常完成（向后兼容）。
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/interrupts#run-outcomes">AG-UI Run Outcomes</a>
 */
public class RunOutcome {
    /** 结果类型（"success" 或 "interrupt"） */
    private String type;
    /** 中断列表（仅当 type 为 "interrupt" 时有值） */
    private List<Interrupt> interrupts;

    public RunOutcome() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<Interrupt> getInterrupts() {
        return interrupts;
    }

    public void setInterrupts(List<Interrupt> interrupts) {
        this.interrupts = interrupts;
    }
}
