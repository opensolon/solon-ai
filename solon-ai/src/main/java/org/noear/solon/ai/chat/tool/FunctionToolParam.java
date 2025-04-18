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
package org.noear.solon.ai.chat.tool;

/**
 * 函数工具参数
 *
 * @author noear
 * @since 3.1
 */
public interface FunctionToolParam {
    /**
     * 参数名字
     */
    String name();

    /**
     * 参数类型
     */
    Class<?> type();

    /**
     * 参数描述
     */
    String description();

    /**
     * 是否必须
     */
    boolean required();
}
