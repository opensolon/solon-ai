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
package org.noear.solon.expression.query;

import org.noear.solon.expression.Expression;
import org.noear.solon.expression.ExpressionParser;

/**
 * 查询表达式解析器
 *
 * @author noear
 * @since 3.1
 */
import java.io.StringReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class QueryExpressionParser implements ExpressionParser {

    private final StringReader reader;
    private int ch; // 当前字符

    public QueryExpressionParser(String expression) {
        this.reader = new StringReader(expression);
        nextChar(); // 初始化读取第一个字符
    }

    @Override
    public Expression parse() {
        ConditionNode result = parseExpression();
        if (ch != -1) {
            throw new RuntimeException("Unexpected character: " + (char) ch);
        }
        return result;
    }

    private void nextChar() {
        try {
            ch = reader.read();
        } catch (IOException e) {
            throw new RuntimeException("Error reading character", e);
        }
    }

    private ConditionNode parseExpression() {
        ConditionNode result = parseTerm();
        while (true) {
            if (eat("OR")) {
                result = new LogicalNode(LogicalOp.or, result, parseTerm());
            } else {
                return result;
            }
        }
    }

    private ConditionNode parseTerm() {
        ConditionNode result = parseFactor();
        while (true) {
            if (eat("AND")) {
                result = new LogicalNode(LogicalOp.and, result, parseFactor());
            } else {
                return result;
            }
        }
    }

    private ConditionNode parseFactor() {
        skipWhitespace();
        ConditionNode result;
        if (eat('(')) {
            result = parseExpression();
            eat(')');
        } else if (eat("NOT")) {
            result = new LogicalNode(LogicalOp.not, parseFactor(), null);
        } else {
            String fieldName = parseIdentifier();
            skipWhitespace();
            if (ch == '>' || ch == '<' || ch == '=' || ch == '!') {
                String operator = parseComparisonOperator();
                Object value = parseValue();
                result = new ComparisonNode(ComparisonOp.parse(operator), new FieldNode(fieldName), new ValueNode(value));
            } else if (eat("IN")) {
                List<Object> values = parseList();
                result = new ComparisonNode(ComparisonOp.in, new FieldNode(fieldName), new ValueNode(values));
            } else if (eat("NOT IN")) {
                List<Object> values = parseList();
                result = new ComparisonNode(ComparisonOp.nin, new FieldNode(fieldName), new ValueNode(values));
            } else {
                result = new ComparisonNode(ComparisonOp.eq, new FieldNode(fieldName), new ValueNode(true));
            }
        }
        return result;
    }

    private String parseIdentifier() {
        StringBuilder sb = new StringBuilder();
        while (Character.isLetterOrDigit(ch) || ch == '_') {
            sb.append((char) ch);
            nextChar();
        }
        return sb.toString();
    }

    private String parseComparisonOperator() {
        StringBuilder sb = new StringBuilder();
        sb.append((char) ch);
        nextChar();
        if (ch == '=') {
            sb.append((char) ch);
            nextChar();
        }
        return sb.toString();
    }

    private Object parseValue() {
        skipWhitespace();
        if (ch == '\'' || ch == '"') {
            return parseString();
        } else if (Character.isDigit(ch)) {
            return parseNumber();
        } else {
            throw new RuntimeException("Unexpected value: " + (char) ch);
        }
    }

    private String parseString() {
        char quote = (char) ch;
        nextChar();
        StringBuilder sb = new StringBuilder();
        while (ch != quote) {
            sb.append((char) ch);
            nextChar();
        }
        nextChar(); // 跳过结束引号
        return sb.toString();
    }

    private int parseNumber() {
        StringBuilder sb = new StringBuilder();
        while (Character.isDigit(ch)) {
            sb.append((char) ch);
            nextChar();
        }
        return Integer.parseInt(sb.toString());
    }

    private List<Object> parseList() {
        List<Object> values = new ArrayList<>();
        eat('[');
        while (ch != ']') {
            values.add(parseValue());
            skipWhitespace();
            if (ch == ',') {
                nextChar();
                skipWhitespace();
            }
        }
        eat(']');
        return values;
    }

    private boolean eat(String expected) {
        skipWhitespace();
        if (ch == -1) return false;

        // 尝试匹配字符串
        for (int i = 0; i < expected.length(); i++) {
            if (ch != expected.charAt(i)) {
                return false;
            }
            nextChar();
        }
        return true;
    }

    private boolean eat(char expected) {
        skipWhitespace();
        if (ch == expected) {
            nextChar();
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (Character.isWhitespace(ch)) {
            nextChar();
        }
    }
}