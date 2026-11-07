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
package org.noear.solon.ai.harness.extension.goal.validate;

/**
 * 验证「无法判定」（超时、执行异常、沙盒不可用、无执行目录）时的处理策略
 *
 * @author noear
 * @since 4.2.0
 */
public enum ValidatorPolicy {
    /** 当作未通过（服务端缺省：验证跑不起来不能算完成） */
    FAIL_CLOSED,
    /** 当作通过（本地编码缺省：环境问题不阻塞完成） */
    FAIL_OPEN
}
