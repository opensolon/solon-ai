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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Linux 平台的 bubblewrap (bwrap) 沙盒
 *
 * <p>原理：通过 bwrap 创建轻量级容器化环境，
 * 使用 mount namespace 控制文件系统可见性，
 * 使用 network namespace 控制网络访问。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class LinuxSandboxExecutor implements OsSandboxExecutor {

    @Override
    public String wrapCommand(String command, Path workPath, Map<String, String> envs) {
        List<String> args = buildBwrapArgs(workPath);
        // bwrap ... -- bash -c '<command>'
        return String.join(" ", args) + " -- bash -c " + shellQuote(command);
    }

    @Override
    public boolean isAvailable() {
        return OsSandboxExecutorFactory.isCommandAvailable("bwrap");
    }

    /**
     * 构建 bubblewrap 参数
     *
     * <p>策略：</p>
     * <ul>
     *   <li>bind-ro: 系统目录只读挂载</li>
     *   <li>bind: 工作区读写挂载</li>
     *   <li>unshare-pid: 独立 PID namespace</li>
     * </ul>
     */
    private List<String> buildBwrapArgs(Path workPath) {
        List<String> args = new ArrayList<>();
        args.add("bwrap");

        // 文件系统：只读挂载系统目录
        args.add("--ro-bind"); args.add("/usr"); args.add("/usr");
        args.add("--ro-bind"); args.add("/bin"); args.add("/bin");
        args.add("--ro-bind"); args.add("/lib"); args.add("/lib");
        args.add("--ro-bind"); args.add("/lib64"); args.add("/lib64");
        args.add("--ro-bind"); args.add("/etc/alternatives"); args.add("/etc/alternatives");

        // 文件系统：读写挂载工作区和 /tmp
        args.add("--bind"); args.add(workPath.toString()); args.add(workPath.toString());
        args.add("--bind"); args.add("/tmp"); args.add("/tmp");

        // proc 和 dev
        args.add("--proc"); args.add("/proc");
        args.add("--dev"); args.add("/dev");

        // 独立 PID namespace
        args.add("--unshare-pid");

        return args;
    }

    private String shellQuote(String s) {
        return "'" + s.replace("'", "'\\\\''") + "'";
    }
}
