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
package org.noear.solon.ai.harness.command;

import org.noear.solon.ai.mount.source.MountSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 命令注册表（通用，CLI 和 Web 共用）
 *
 * @author noear
 * @since 4.2.0
 */
public class CommandRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<String, Command> commands = new ConcurrentHashMap<>();

    /**
     * 从挂载来源的目录加载（本地、db、http 均可；本地目录用 {@code FileMountSource.of(dir)}）。
     * 目录不存在时静默返回。
     *
     * @param source 挂载来源
     * @param dir    来源内目录（空串表示根）
     */
    public void load(MountSource source, String dir) {
        assertNotSealed("load");
        MarkdownCommandLoader.loadFrom(source, dir, this);
    }

    private volatile boolean sealed;

    /**
     * 封口（SERVER 模式在 {@code build()} 末尾调用）：之后 {@link #register} / {@link #load} 抛
     * {@link IllegalStateException}。命令对同引擎的所有用户可见，应在构建期决定（{@code Builder.commandAdd}）。
     *
     * @since 4.2.0
     */
    public void seal() {
        this.sealed = true;
    }

    public boolean isSealed() {
        return sealed;
    }

    private void assertNotSealed(String op) {
        if (sealed) {
            throw new IllegalStateException("CommandRegistry is sealed (runtimeMutable=false): " + op
                    + "; register commands at build time with HarnessEngine.Builder.commandAdd / commandLoad");
        }
    }

    /**
     * 注册命令
     *
     * @param command 命令
     */
    public void register(Command command) {
        assertNotSealed("register");
        Command existing = commands.putIfAbsent(command.name(), command);

        if (existing != null) {
            LOG.warn("Command '{}' already registered by {}, skip: {}",
                    command.name(),
                    existing.getClass().getSimpleName(),
                    command.getClass().getSimpleName());
        }
    }

    /**
     * 查找命令（已启用）
     * <p>
     * 未注册与已停用都返回 null —— 需要区分两者的调用方（如命令分发要给出「已停用」回执）
     * 请改用 {@link #get(String)} 再自行判 {@code isEnabled()}。
     */
    public Command find(String name) {
        Command tmp = get(name);
        if (tmp != null && tmp.isEnabled()) {
            return tmp;
        } else {
            return null;
        }
    }

    /**
     * 获取命令（不过滤启用状态）
     * <p>
     * 用于需要区分「未注册」与「已停用」的场景：前者应视为普通输入，后者应回执不可用提示。
     */
    public Command get(String name) {
        return commands.get(name);
    }

    /**
     * 获取命令名列表（用于 Tab 补全）
     */
    public List<String> names() {
        return commands.values().stream()
                .filter(Command::isEnabled)
                .map(Command::name)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 获取所有命令（排序后，仅已启用）
     * <p>
     * 已停用的命令对用户不可见，/help 列表须与 {@link #names()} 的补全集合一致。
     */
    public List<Command> all() {
        return commands.values().stream()
                .filter(Command::isEnabled)
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList());
    }
}