/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.lsp;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LSP 服务器配置参数（对齐 OpenCode 的 lsp 配置模式）
 *
 * <pre>
 * // config.yml 配置示例：
 * lspServers:
 *   java:
 *     command: ["jdtls", "-data", ".solon/lsp/java-workspace"]
 *     extensions: [".java"]
 *   typescript:
 *     command: ["typescript-language-server", "--stdio"]
 *     extensions: [".ts", ".tsx", ".js", ".jsx"]
 *   go:
 *     command: ["gopls"]
 *     extensions: [".go"]
 *   python:
 *     command: ["pylsp"]
 *     extensions: [".py"]
 *   disabled:
 *     command: ["some-server"]
 *     extensions: [".xx"]
 *     disabled: true
 * </pre>
 *
 * @author noear
 * @since 3.10.0
 */
public class LspServerParameters implements Serializable {
    /**
     * 启动命令（必填）
     */
    private List<String> command = new ArrayList<>();

    /**
     * 关联的文件扩展名（必填），如 [".java", ".kt"]
     */
    private List<String> extensions = new ArrayList<>();

    /**
     * 是否禁用（可选，默认 false）
     */
    private boolean disabled = false;

    /**
     * 服务器初始化选项（可选）
     */
    private Map<String, Object> initialization = new HashMap<>();

    /**
     * 额外环境变量（可选）
     */
    private Map<String, String> env = new HashMap<>();

    public LspServerParameters() {
    }

    public LspServerParameters(List<String> command, List<String> extensions) {
        this.command = command;
        this.extensions = extensions;
    }

    /// ///////////// getter/setter //////////////

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command;
    }

    public List<String> getExtensions() {
        return extensions;
    }

    public void setExtensions(List<String> extensions) {
        this.extensions = extensions;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public Map<String, Object> getInitialization() {
        return initialization;
    }

    public void setInitialization(Map<String, Object> initialization) {
        this.initialization = initialization;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    /**
     * 获取命令数组
     */
    public String[] getCommandArray() {
        return command.toArray(new String[0]);
    }

    /**
     * 判断是否匹配给定文件路径（基于扩展名）
     */
    public boolean matchesExtension(String filePath) {
        if (extensions == null || extensions.isEmpty()) {
            return false;
        }
        String lowerPath = filePath.toLowerCase();
        for (String ext : extensions) {
            if (lowerPath.endsWith(ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
