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

import java.util.*;

/**
 * @author noear
 * @since 3.1
 */
public class McpServerParameters {
    private String command;
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new HashMap<>();

    /// ////////////

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public static Builder builder(String command){
        return new Builder(command);
    }

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
