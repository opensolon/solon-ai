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
package org.noear.solon.expression.template;

import org.noear.solon.core.util.TmplUtil;
import org.noear.solon.expression.Expression;
import org.noear.solon.expression.ExpressionContext;

/**
 * 模板表达式
 *
 * @author noear
 * @since 3.1
 */
public class TemplateExpression implements Expression<String> {
    private String expr;

    public TemplateExpression(String expr) {
        this.expr = expr;
    }

    @Override
    public String evaluate(ExpressionContext context) {
        return TmplUtil.parse(expr, context::containsKey, context::getValue);
    }
}
