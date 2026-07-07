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
package org.noear.solon.ai.harness.permission;

/**
 * 权限模式，控制整体权限行为
 *
 * <p>对应 claude-code-java 的 PermissionMode，适配 HITL 框架场景。</p>
 *
 * @author noear
 * @since 4.0
 */
public enum PermissionMode {
    /** 默认模式 — 按规则评估，无规则时询问用户 */
    DEFAULT,
    /** 只读模式，拒绝所有写操作 */
    READ_ONLY,
    /** 绕过模式 — 放行所有操作 */
    BYPASS,
    /** 接受文件编辑，写操作放行但读操作仍需确认 */
    ACCEPT_EDITS
}
