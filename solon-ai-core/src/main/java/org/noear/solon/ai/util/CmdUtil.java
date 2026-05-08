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
package org.noear.solon.ai.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令解析工具
 *
 * @author noear 2026/5/6 created
 * @since 3.10.5
 */
public class CmdUtil {
    public static List<String> parseArguments(String input) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inQuotes) {
                // 在引号内，无论什么字符都直接追加（包括空格）
                current.append(c);
                if (c == quoteChar) { // 遇到匹配的结束引号
                    inQuotes = false;
                }
            } else {
                if (c == '\"' || c == '\'') { // 遇到开始引号
                    inQuotes = true;
                    quoteChar = c;
                    current.append(c); // 将起始引号存入
                } else if (Character.isWhitespace(c)) { // 引号外的空格作为分隔符
                    if (current.length() > 0) {
                        args.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }
}