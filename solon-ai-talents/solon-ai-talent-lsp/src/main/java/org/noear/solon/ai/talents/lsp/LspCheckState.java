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

/**
 * 单次写入后的 LSP 检查状态
 *
 * <p>存在的理由是 {@link #CLEAN} 与 {@link #PENDING} 必须分开：只知道「这个文件类型有语言
 * 服务器」不足以断言「这次写入被检查过且没有错误」。语言服务器冷启动（首次索引）常常超出
 * 诊断等待预算，此时既没有诊断文本也没有结论，若与真正的无错误混为一谈，展示层就会给出
 * 比实际更强的确定性保证。
 *
 * @author noear
 * @since 4.1
 */
public enum LspCheckState {
    /**
     * 无语言服务器覆盖（未启用、扩展名不匹配、或服务器已标记启动失败）：不该做任何断言
     */
    NONE,

    /**
     * 已请求检查，但在等待预算内没拿到本轮诊断结论（多为冷启动首次索引）：结果未知
     */
    PENDING,

    /**
     * 语言服务器已给出本轮结论：没有 ERROR 级诊断
     */
    CLEAN,

    /**
     * 语言服务器已给出本轮结论：存在 ERROR 级诊断（明细走诊断文本通道）
     */
    ERRORS
}
