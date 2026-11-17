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
package features.ai.talent.gateway;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.talents.gateway.ToolSignature;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具参数签名的生成规则
 */
public class ToolSignatureTest {
    private static String sig(String properties) {
        return ToolSignature.of("t", "{\"type\":\"object\",\"properties\":{" + properties + "}}");
    }

    private static String sig(String properties, String required) {
        return ToolSignature.of("t", "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":[" + required + "]}");
    }

    @Test
    void primitivesAndOptional() {
        assertEquals("t(a: string, b: integer, c?: number, d?: boolean)",
                sig("\"c\":{\"type\":\"number\"},\"a\":{\"type\":\"string\"},\"d\":{\"type\":\"boolean\"},\"b\":{\"type\":\"integer\"}",
                        "\"a\",\"b\""),
                "必填在前，各自保持 Schema 顺序");
    }

    @Test
    void typeArray() {
        assertEquals("t(a?: string|null)", sig("\"a\":{\"type\":[\"string\",\"null\"]}"));
        assertEquals("t(a?: string[]|null)", sig("\"a\":{\"type\":[\"array\",\"null\"],\"items\":{\"type\":\"string\"}}"));
    }

    @Test
    void enums() {
        assertEquals("t(mode: \"fast\"|\"slow\")", sig("\"mode\":{\"type\":\"string\",\"enum\":[\"fast\",\"slow\"]}", "\"mode\""));
        assertEquals("t(level?: 1|2|3)", sig("\"level\":{\"type\":\"integer\",\"enum\":[1,2,3]}"));
        assertEquals("t(a?: string)", sig("\"a\":{\"type\":\"string\",\"enum\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"), "超过 5 个值写基础类型");
        assertEquals("t(a?: string)", sig("\"a\":{\"type\":\"string\",\"enum\":[\"aaaaaaaaaaaaaaaaaaaa\",\"bbbbbbbbbbbbbbbbbbbbbbb\"]}"), "超过 40 字写基础类型");
    }

    @Test
    void arraysAndObjects() {
        assertEquals("t(tags?: string[])", sig("\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
        assertEquals("t(rows?: array)", sig("\"rows\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}"));
        assertEquals("t(rows?: array)", sig("\"rows\":{\"type\":\"array\"}"), "没写 items");
        assertEquals("t(opts?: object)", sig("\"opts\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}"));
        assertEquals("t(opts?: object)", sig("\"opts\":{\"properties\":{\"x\":{\"type\":\"string\"}}}"), "没写 type 但有 properties");
    }

    @Test
    void unionsAndAny() {
        // Pydantic Optional[str]
        assertEquals("t(a?: string|null)", sig("\"a\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}],\"default\":null}"));
        assertEquals("t(a?: integer|string)", sig("\"a\":{\"oneOf\":[{\"type\":\"integer\"},{\"type\":\"string\"}]}"));
        assertEquals("t(a?: any)", sig("\"a\":{\"anyOf\":[{\"type\":\"object\"},{\"type\":\"null\"}]}"), "分支含对象");
        assertEquals("t(a?: any)", sig("\"a\":{\"anyOf\":[{\"$ref\":\"#/$defs/X\"},{\"type\":\"null\"}]}"));
        assertEquals("t(a?: any)", sig("\"a\":{\"allOf\":[{\"$ref\":\"#/$defs/X\"}]}"));
        assertEquals("t(a?: any)", sig("\"a\":{\"$ref\":\"#/$defs/X\"}"));
        assertEquals("t(a?: any)", sig("\"a\":{\"description\":\"no type\"}"));
        assertEquals("t(a?: any)", sig("\"a\":{\"type\":\"date\"}"), "未知类型");
    }

    @Test
    void tooManyParams() {
        StringBuilder props = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                props.append(',');
            }
            props.append("\"p").append(i).append("\":{\"type\":\"string\"}");
        }
        assertEquals("t(p1?: string, p2?: string, p3?: string, p4?: string, p5?: string, p6?: string, p7?: string, p8?: string, …)",
                sig(props.toString()));
    }

    @Test
    void lengthCutAtParamBoundary() {
        StringBuilder props = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (i > 1) {
                props.append(',');
            }
            props.append("\"parameter_with_a_rather_long_name_").append(i).append("\":{\"type\":\"string\"}");
        }
        String s = sig(props.toString());
        assertTrue(s.length() <= ToolSignature.MAX_LENGTH, s);
        assertTrue(s.endsWith(", …)"), s);
        assertFalse(s.contains("parameter_with_a_rather_long_name_6"), s);

        // 第一个参数就超长：只留省略号
        String longName = new String(new char[250]).replace('\0', 'x');
        assertEquals("t(…)", sig("\"" + longName + "\":{\"type\":\"string\"}"));
    }

    @Test
    void brokenOrEmptySchema() {
        assertEquals("t()", ToolSignature.of("t", "{not json"));
        assertEquals("t()", ToolSignature.of("t", null));
        assertEquals("t()", ToolSignature.of("t", ""));
        assertEquals("t()", ToolSignature.of("t", "{\"type\":\"object\"}"));
        assertEquals("t()", ToolSignature.of("t", "{\"type\":\"object\",\"properties\":{}}"));
        assertEquals("t()", ToolSignature.of("t", "[1,2]"));
        assertEquals("t(a?: string)", ToolSignature.of("t", "{\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":\"a\"}"),
                "required 不是数组时按全部可选处理");
    }

    @Test
    void ofFunctionTool() {
        assertEquals("echo(q: string)", ToolSignature.of(new FunctionToolDesc("echo")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}")
                .doHandle(args -> ToolResult.success("ok"))));
    }
}
