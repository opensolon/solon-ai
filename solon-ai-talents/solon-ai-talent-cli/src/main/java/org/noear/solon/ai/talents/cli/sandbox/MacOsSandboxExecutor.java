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

import java.nio.file.Path;
import java.util.Map;

/**
 * macOS 平台的 sandbox-exec (Seatbelt) 沙盒
 *
 * <p>原理：通过 sandbox-exec -p &lt;profile&gt; &lt;command&gt; 包装命令，
 * 利用 macOS 内核级的 Seatbelt 框架强制执行文件访问控制。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class MacOsSandboxExecutor implements OsSandboxExecutor {

    @Override
    public String wrapCommand(String command, Path workPath, Map<String, String> envs) {
        String profile = generateSeatbeltProfile(workPath);
        // sandbox-exec -p '<profile>' bash -c '<command>'
        return "sandbox-exec -p '" + profile + "' bash -c " + shellQuote(command);
    }

    @Override
    public boolean isAvailable() {
        return OsSandboxExecutorFactory.isCommandAvailable("sandbox-exec");
    }

    /**
     * 生成 Seatbelt 策略
     *
     * <p>策略设计（参考 Anthropic sandbox-runtime）：</p>
     * <ul>
     *   <li>默认拒绝所有</li>
     *   <li>写操作：显式白名单放行工作区和 /tmp</li>
     *   <li>读操作：允许工作区、系统必要路径</li>
     * </ul>
     */
    private String generateSeatbeltProfile(Path workPath) {
        String wp = workPath.toString();
        StringBuilder sb = new StringBuilder();
        sb.append("(version 1)\\n");
        sb.append("(deny default)\\n");  // 默认拒绝所有

        // 允许在工作区内读写
        sb.append("(allow file-read* file-write* (subpath \"").append(wp).append("\"))\\n");

        // 允许读取系统必要路径（bash/python/node 需要加载库）
        sb.append("(allow file-read* (subpath \"/usr\"))\\n");
        sb.append("(allow file-read* (subpath \"/bin\"))\\n");
        sb.append("(allow file-read* (subpath \"/lib\"))\\n");
        sb.append("(allow file-read* (subpath \"/System\"))\\n");
        sb.append("(allow file-read* (subpath \"/tmp\"))\\n");
        sb.append("(allow file-read* (subpath \"/var/tmp\"))\\n");

        // 允许写入 /tmp（很多工具依赖）
        sb.append("(allow file-write* (subpath \"/tmp\"))\\n");

        // 允许进程创建（但受 ulimit 约束）
        sb.append("(allow process*)\\n");

        // 网络策略：默认允许（如需严格隔离可改为 deny）
        sb.append("(allow network*)\\n");

        return sb.toString();
    }

    private String shellQuote(String s) {
        return "'" + s.replace("'", "'\\\\''") + "'";
    }
}
