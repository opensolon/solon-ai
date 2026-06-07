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
 * 沙盒完整配置（对应 Anthropic sandbox-runtime 的 SandboxRuntimeConfig）
 *
 * @author noear
 * @since 3.9.1
 */
public class SandboxConfig {
    private SandboxFsConfig filesystem = new SandboxFsConfig();
    private SandboxNetConfig network = new SandboxNetConfig();
    private boolean enableViolationMonitor = true;

    public SandboxFsConfig getFilesystem() {
        return filesystem;
    }

    public void setFilesystem(SandboxFsConfig filesystem) {
        this.filesystem = filesystem != null ? filesystem : new SandboxFsConfig();
    }

    public SandboxNetConfig getNetwork() {
        return network;
    }

    public void setNetwork(SandboxNetConfig network) {
        this.network = network != null ? network : new SandboxNetConfig();
    }

    public boolean isEnableViolationMonitor() {
        return enableViolationMonitor;
    }

    public void setEnableViolationMonitor(boolean enableViolationMonitor) {
        this.enableViolationMonitor = enableViolationMonitor;
    }
}
