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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 沙盒文件系统配置
 *
 * <p>读写策略（与 Anthropic sandbox-runtime 一致）：</p>
 * <ul>
 *   <li>读（deny-then-allow）：默认允许所有读，denyRead 黑名单禁止，allowRead 在黑名单内打洞</li>
 *   <li>写（allow-only）：默认拒绝所有写，allowWrite 白名单放行，denyWrite 在白名单内排除</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.1
 */
public class SandboxFsConfig {
    // 读限制（deny-then-allow 模式）
    private List<String> denyRead = Collections.emptyList();
    private List<String> allowRead = Collections.emptyList();

    // 写限制（allow-only 模式）
    private List<String> allowWrite = Arrays.asList(".", "/tmp");
    private List<String> denyWrite = Collections.emptyList();

    /**
     * 始终禁止写入的文件（强制拒绝，即使用户配置了 allowWrite）
     */
    private static final List<String> MANDATORY_DENY_FILES = Arrays.asList(
            ".gitconfig", ".gitmodules",
            ".bashrc", ".bash_profile", ".bash_logout",
            ".zshrc", ".zprofile",
            ".profile",
            ".ripgreprc",
            ".mcp.json",
            ".claude/commands", ".claude/agents"
    );

    /**
     * 始终禁止写入的目录模式
     */
    private static final List<String> MANDATORY_DENY_DIRS = Arrays.asList(
            ".vscode", ".idea",
            ".git/hooks"
    );

    public List<String> getDenyRead() {
        return denyRead;
    }

    public void setDenyRead(List<String> denyRead) {
        this.denyRead = denyRead != null ? denyRead : Collections.emptyList();
    }

    public List<String> getAllowRead() {
        return allowRead;
    }

    public void setAllowRead(List<String> allowRead) {
        this.allowRead = allowRead != null ? allowRead : Collections.emptyList();
    }

    public List<String> getAllowWrite() {
        return allowWrite;
    }

    public void setAllowWrite(List<String> allowWrite) {
        this.allowWrite = allowWrite != null ? allowWrite : Collections.emptyList();
    }

    public List<String> getDenyWrite() {
        return denyWrite;
    }

    public void setDenyWrite(List<String> denyWrite) {
        this.denyWrite = denyWrite != null ? denyWrite : Collections.emptyList();
    }

    /**
     * 获取所有强制拒绝的文件名模式
     */
    public static List<String> getMandatoryDenyFiles() {
        return MANDATORY_DENY_FILES;
    }

    /**
     * 获取所有强制拒绝的目录模式
     */
    public static List<String> getMandatoryDenyDirs() {
        return MANDATORY_DENY_DIRS;
    }

    /**
     * 获取有效的写拒绝路径（合并用户配置 + 强制拒绝）
     */
    public List<String> getEffectiveDenyWrite(String workPath) {
        List<String> effective = new ArrayList<>(denyWrite);
        for (String f : MANDATORY_DENY_FILES) {
            effective.add(workPath + "/" + f);
        }
        for (String d : MANDATORY_DENY_DIRS) {
            effective.add(workPath + "/" + d);
        }
        return effective;
    }

    /**
     * 检查给定路径是否匹配强制拒绝的文件
     */
    public static boolean isMandatoryDenyPath(String relativePath) {
        if (relativePath == null) return false;
        String normalized = relativePath.startsWith("./") ? relativePath.substring(2) : relativePath;
        for (String denyFile : MANDATORY_DENY_FILES) {
            if (normalized.equals(denyFile) || normalized.endsWith("/" + denyFile)) {
                return true;
            }
        }
        for (String denyDir : MANDATORY_DENY_DIRS) {
            if (normalized.equals(denyDir) || normalized.startsWith(denyDir + "/")
                    || normalized.endsWith("/" + denyDir)
                    || normalized.contains("/" + denyDir + "/")) {
                return true;
            }
        }
        return false;
    }
}
