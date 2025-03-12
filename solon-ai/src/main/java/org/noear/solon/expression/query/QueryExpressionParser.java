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

    private final String expression;

    public QueryExpressionParser(String expression) {
        this.expression = expression;
    }

    @Override
    public Expression parse() {
        ParserState state = new ParserState(new StringReader(expression));
        ConditionNode result = parseExpression(state);
        if (state.getCurrentChar() != -1) {
            throw new RuntimeException("Unexpected character: " + (char) state.getCurrentChar());
        }
        return result;
    }

    private ConditionNode parseExpression(ParserState state) {
        ConditionNode result = parseTerm(state);
        while (true) {
            if (eat(state, "OR")) {
                result = new LogicalNode(LogicalOp.or, result, parseTerm(state));
            } else {
                return result;
            }
        }
    }

    private ConditionNode parseTerm(ParserState state) {
        ConditionNode result = parseFactor(state);
        while (true) {
            if (eat(state, "AND")) {
                result = new LogicalNode(LogicalOp.and, result, parseFactor(state));
            } else {
                return result;
            }
        }
    }

    private ConditionNode parseFactor(ParserState state) {
        state.skipWhitespace();
        ConditionNode result;
        if (eat(state, '(')) {
            result = parseExpression(state);
            eat(state, ')');
        } else if (eat(state, "NOT")) {
            result = new LogicalNode(LogicalOp.not, parseFactor(state), null);
        } else {
            String fieldName = parseIdentifier(state);
            state.skipWhitespace();
            if (state.getCurrentChar() == '>' || state.getCurrentChar() == '<' || state.getCurrentChar() == '=' || state.getCurrentChar() == '!') {
                String operator = parseComparisonOperator(state);
                Object value = parseValue(state, true);
                Expression rightNode = null;
                if(value instanceof Expression){
                    rightNode = (Expression) value;
                }else{
                    rightNode = new ConstantNode(value);
                }

                result = new ComparisonNode(ComparisonOp.parse(operator), new VariableNode(fieldName), rightNode);
            } else if (eat(state, "IN")) {
                List<Object> values = parseList(state);
                result = new ComparisonNode(ComparisonOp.in, new VariableNode(fieldName), new ConstantNode(values));
            } else if (eat(state, "NOT IN")) {
                List<Object> values = parseList(state);
                result = new ComparisonNode(ComparisonOp.nin, new VariableNode(fieldName), new ConstantNode(values));
            } else {
                result = new ComparisonNode(ComparisonOp.eq, new VariableNode(fieldName), new ConstantNode(true));
            }
        }
        return result;
    }

    private String parseIdentifier(ParserState state) {
        StringBuilder sb = new StringBuilder();
        while (state.isIdentifier()) {
            sb.append((char) state.getCurrentChar());
            state.nextChar();
        }
        return sb.toString();
    }

    private String parseComparisonOperator(ParserState state) {
        StringBuilder sb = new StringBuilder();
        sb.append((char) state.getCurrentChar());
        state.nextChar();
        if (state.getCurrentChar() == '=') {
            sb.append((char) state.getCurrentChar());
            state.nextChar();
        }
        return sb.toString();
    }

    private Object parseValue(ParserState state, boolean allowVariable) {
        state.skipWhitespace();
        if (state.getCurrentChar() == '\'' || state.getCurrentChar() == '"') {
            return parseString(state);
        } else if (Character.isDigit(state.getCurrentChar())) {
            return parseNumber(state);
        } else {
            String tmp = parseIdentifier(state);
            if ("true".equals(tmp) || "false".equals(tmp)) {
                return Boolean.parseBoolean(tmp);
            } else {
                if (allowVariable) {
                    return new VariableNode(tmp);
                } else {
                    throw new RuntimeException("Unexpected value: " + tmp);
                }
            }
        }
    }

    private String parseString(ParserState state) {
        char quote = (char) state.getCurrentChar();
        state.nextChar();
        StringBuilder sb = new StringBuilder();
        while (state.getCurrentChar() != quote) {
            sb.append((char) state.getCurrentChar());
            state.nextChar();
        }
        state.nextChar(); // 跳过结束引号
        return sb.toString();
    }

    private int parseNumber(ParserState state) {
        StringBuilder sb = new StringBuilder();
        while (Character.isDigit(state.getCurrentChar())) {
            sb.append((char) state.getCurrentChar());
            state.nextChar();
        }
        return Integer.parseInt(sb.toString());
    }

    private List<Object> parseList(ParserState state) {
        List<Object> values = new ArrayList<>();
        eat(state, '[');
        while (state.getCurrentChar() != ']') {
            values.add(parseValue(state, false));
            state.skipWhitespace();
            if (state.getCurrentChar() == ',') {
                state.nextChar();
                state.skipWhitespace();
            }
        }
        eat(state, ']');
        return values;
    }

    private boolean eat(ParserState state, String expected) {
        state.skipWhitespace();
        if (state.getCurrentChar() == -1) return false;

        // 尝试匹配字符串
        for (int i = 0; i < expected.length(); i++) {
            if (state.getCurrentChar() != expected.charAt(i)) {
                return false;
            }
            state.nextChar();
        }
        return true;
    }

    private boolean eat(ParserState state, char expected) {
        state.skipWhitespace();
        if (state.getCurrentChar() == expected) {
            state.nextChar();
            return true;
        }
        return false;
    }

    /**
     * 封装解析器的状态，包括 StringReader 和当前字符。
     */
    private static class ParserState {
        private final StringReader reader;
        private int ch; // 当前字符

        public ParserState(StringReader reader) {
            this.reader = reader;
            this.ch = -1; // 初始状态
            nextChar(); // 初始化读取第一个字符
        }

        public int getCurrentChar() {
            return ch;
        }

        public void nextChar() {
            try {
                ch = reader.read();
            } catch (IOException e) {
                throw new RuntimeException("Error reading character", e);
            }
        }

        public void skipWhitespace() {
            while (Character.isWhitespace(ch)) {
                nextChar();
            }
        }

        public boolean isString() {
            return getCurrentChar() == '\'' || getCurrentChar() == '"';
        }

        public boolean isBoolean() {
            return getCurrentChar() == 'f' || getCurrentChar() == 't';
        }

        public boolean isIdentifier() {
            return Character.isLetterOrDigit(getCurrentChar()) || getCurrentChar() == '_';
        }
    }
}