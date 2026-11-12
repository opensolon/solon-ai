/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.queue.i18n;

import org.noear.solon.lang.Preview;

/**
 * queue 插件的文案 key（常量名 = key 转大写，"." 换成 "_"）
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class QueueI18nKeys {
    private QueueI18nKeys() {
    }

    /** 门面入队失败（队列已满）的说明 */
    public static final String QUEUE_FULL_DETAIL = "queue.full_detail";

    public static final String COMMAND_QUEUE_DESCRIPTION = "command.queue.description";
    public static final String COMMAND_QUEUE_EXAMPLE_MESSAGE = "command.queue.example_message";
    public static final String COMMAND_QUEUE_EXAMPLE_CANCEL = "command.queue.example_cancel";
    /** 用法（多行：每行单独输出） */
    public static final String COMMAND_QUEUE_USAGE = "command.queue.usage";
    public static final String COMMAND_QUEUE_TOO_LONG = "command.queue.too_long";
    public static final String COMMAND_QUEUE_FULL = "command.queue.full";
    public static final String COMMAND_QUEUE_SCOPE_NOT_DEFERRABLE = "command.queue.scope_not_deferrable";
    public static final String COMMAND_QUEUE_ENQUEUED = "command.queue.enqueued";
    public static final String COMMAND_QUEUE_LIST_EMPTY = "command.queue.list_empty";
    public static final String COMMAND_QUEUE_LIST_TITLE = "command.queue.list_title";
    /** 列表项的执行中标记（结尾空格由代码补） */
    public static final String COMMAND_QUEUE_LIST_RUNNING = "command.queue.list_running";
    public static final String COMMAND_QUEUE_LIST_FOOTER = "command.queue.list_footer";
    public static final String COMMAND_QUEUE_CANCEL_RUNNING = "command.queue.cancel_running";
    public static final String COMMAND_QUEUE_CANCELLED = "command.queue.cancelled";
    public static final String COMMAND_QUEUE_CANCEL_NOT_FOUND = "command.queue.cancel_not_found";
    public static final String COMMAND_QUEUE_CLEARED = "command.queue.cleared";
    public static final String COMMAND_QUEUE_CLEAR_EMPTY = "command.queue.clear_empty";
}
