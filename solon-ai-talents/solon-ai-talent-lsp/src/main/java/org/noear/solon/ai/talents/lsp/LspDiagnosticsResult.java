/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.lsp;

import org.eclipse.lsp4j.Diagnostic;

import java.util.Collections;
import java.util.List;

/**
 * 诊断等待结果：诊断列表 + 本轮结论是否确认
 *
 * <p>{@code confirmed=false} 时列表可能只是上一轮的残留（或为空），不代表「没有错误」。
 * 空列表 + 未确认与空列表 + 已确认在语义上截然不同，故不能只用 {@code List} 表达。
 *
 * @author noear
 * @since 4.1
 */
public final class LspDiagnosticsResult {
    private final List<Diagnostic> items;
    private final boolean confirmed;

    private LspDiagnosticsResult(List<Diagnostic> items, boolean confirmed) {
        this.items = (items == null) ? Collections.<Diagnostic>emptyList() : items;
        this.confirmed = confirmed;
    }

    /**
     * 已收到本轮（版本对齐的）诊断推送
     */
    public static LspDiagnosticsResult confirmed(List<Diagnostic> items) {
        return new LspDiagnosticsResult(items, true);
    }

    /**
     * 等待超时或无从判断：列表仅供参考
     */
    public static LspDiagnosticsResult unconfirmed(List<Diagnostic> items) {
        return new LspDiagnosticsResult(items, false);
    }

    public List<Diagnostic> getItems() {
        return items;
    }

    public boolean isConfirmed() {
        return confirmed;
    }
}
