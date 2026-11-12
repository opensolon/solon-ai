/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.goal;

/**
 * 本会话已有进行中的 goal（创建被拒）
 *
 * <p>说明按引擎实例的语言生成（门面直接用它）；/goal 命令按命令上下文的语言另行输出。</p>
 *
 * @author noear
 * @since 4.2
 */
class GoalActiveException extends IllegalStateException {
    GoalActiveException(String message) {
        super(message);
    }
}
