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
package org.noear.solon.expr.query;

/**
 * 字段节点（表示查询中的字段）
 *
 * @author noear
 * @since 3.1
 */
public class FieldNode implements ExprNode {
    private String fieldName;

    public FieldNode(String fieldName) {
        this.fieldName = fieldName;
    }

    /**
     * 获取字段名
     */
    public String getFieldName() {
        return fieldName;
    }
}