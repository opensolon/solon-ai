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
package org.noear.solon.ai.mcp.client;

import org.noear.solon.Utils;
import org.noear.solon.core.util.Assert;

import java.io.Serializable;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Mcp 服务端配置参数
 *
 * @author noear
 * @since 3.1
 */
public class McpServerParameters implements Serializable {
    private String transport;
    private String type;

    private String url;
    private Map<String, String> headers = new HashMap<>();
    private Duration timeout;

    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new HashMap<>();

    public McpServerParameters() {
        //反序列化用
    }

    /// ////////////
    ///
    ///

    public McpServerParameters then(Consumer<McpServerParameters> build) {
        build.accept(this);
        return this;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void addHeaderVar(String key, String value) {
        Assert.notNull(key, "The key can not be null");
        Assert.notNull(value, "The value can not be null");

        headers.put(key, value);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /// ////////////

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        Assert.notNull(command, "The command can not be null");

        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        Assert.notNull(args, "The args can not be null");

        this.args = args;
    }

    public void addArgVar(String arg) {
        Assert.notNull(arg, "The arg can not be null");
        this.args.add(arg);
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public void addEnvVar(String key, String value) {
        Assert.notNull(key, "The key can not be null");
        Assert.notNull(value, "The value can not be null");

        this.env.put(key, value);
    }

    /**
     * @deprecated 3.10.4
     *
     */
    @Deprecated
    public static Builder builder(String command) {
        return new Builder(command);
    }

    /**
     * @deprecated 3.10.4
     *
     */
    @Deprecated
    public static class Builder {
        private McpServerParameters params = new McpServerParameters();

        public Builder(String command) {
            Assert.notNull(command, "The command can not be null");
            params.command = command;
        }

        public McpServerParameters.Builder args(String... args) {
            Assert.notNull(args, "The args can not be null");
            params.args = Arrays.asList(args);
            return this;
        }

        public McpServerParameters.Builder args(List<String> args) {
            Assert.notNull(args, "The args can not be null");
            params.args = new ArrayList<>(args);
            return this;
        }

        public McpServerParameters.Builder arg(String arg) {
            Assert.notNull(arg, "The arg can not be null");
            params.args.add(arg);
            return this;
        }

        public McpServerParameters.Builder env(Map<String, String> env) {
            if (Utils.isNotEmpty(env)) {
                params.env.putAll(env);
            }
            return this;
        }

        public McpServerParameters.Builder addEnvVar(String key, String value) {
            Assert.notNull(key, "The key can not be null");
            Assert.notNull(value, "The value can not be null");
            params.env.put(key, value);
            return this;
        }

        public McpServerParameters build() {
            return params;
        }

    }
}