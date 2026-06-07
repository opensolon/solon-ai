/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.talents.cli.sandbox;

/**
 * Shell 参数安全转义工具
 *
 * <p>移植自 Anthropic sandbox-runtime 使用的 shell-quote 算法。
 * 将任意字符串安全地编码为 POSIX shell 可解析的单个参数。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class ShellQuote {

    /**
     * 将字符串数组安全编码为 shell 命令行
     */
    public static String quote(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(quote(args[i]));
        }
        return sb.toString();
    }

    /**
     * 安全转义单个 shell 参数
     *
     * <p>策略：对包含安全字符（[a-zA-Z0-9_@%+=:,./-]）的参数不转义，
     * 其余用单引号包裹（内部单引号用 '\'' 转义）。</p>
     */
    public static String quote(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "''";
        }
        // 安全字符集：不包含空格、引号、反斜杠、$、!、`、*、?、[、]、{、}、(、)、<、>、|、;、&、#、~、\n 等
        if (arg.matches("^[a-zA-Z0-9_@%+=:,./-]+$")) {
            return arg;
        }
        return "'" + arg.replace("'", "'\\''") + "'";
    }
}
