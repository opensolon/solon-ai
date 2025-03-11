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
 * 比较操作符
 *
 * @author noear
 * @since 3.1
 */
public enum ComparisonOp {
    lt("<"),  // <
    lte("<="), // <=
    gt(">"),  // >
    gte(">="), // >=
    eq("=="),  // ==
    neq("!="), // !=
    in("in"),  // in
    nin("not in"), // not in
    ;

    ComparisonOp(String code) {
        this.code = code;
    }

    private final String code;

    /**
     * 代号
     */
    public String getCode() {
        return code;
    }

    /**
     * 解析
     */
    public static ComparisonOp parse(String op) {
        switch (op) {
            case "<":
                return lt;
            case "<=":
                return lte;
            case ">":
                return gt;
            case ">=":
                return gte;
            case "==":
                return eq;
            case "!=":
                return neq;
            case "in":
                return in;
            case "not in":
                return nin;
            default:
                throw new IllegalArgumentException("Invalid comparison operator: " + op);
        }
    }
}