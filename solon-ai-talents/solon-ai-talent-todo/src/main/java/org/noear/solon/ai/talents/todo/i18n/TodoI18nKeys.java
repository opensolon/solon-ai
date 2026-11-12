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
package org.noear.solon.ai.talents.todo.i18n;

import org.noear.solon.lang.Preview;

/**
 * todo 才能的文案 key（常量名 = key 转大写，"." 换成 "_"）
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class TodoI18nKeys {
    private TodoI18nKeys() {
    }

    public static final String TODO_DESCRIPTION = "todo.description";
    public static final String TODO_INSTRUCTION = "todo.instruction";
    public static final String TODO_READ_EMPTY = "todo.read.empty";
    /**
     * 变量：total, done, inProgress, pending
     */
    public static final String TODO_FOOTER_PROGRESS = "todo.footer.progress";
    /**
     * 变量：count
     */
    public static final String TODO_FOOTER_CONTINUE = "todo.footer.continue";
    /**
     * 变量：item
     */
    public static final String TODO_FOOTER_NEXT = "todo.footer.next";
    public static final String TODO_FOOTER_DONE = "todo.footer.done";
}
