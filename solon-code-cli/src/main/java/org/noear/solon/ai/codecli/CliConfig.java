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
package org.noear.solon.ai.codecli;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.annotation.BindProps;
import org.noear.solon.annotation.Configuration;

import java.util.Map;

/**
 * Cli 配置
 *
 * @author noear
 * @since 3.9.1
 */
@Configuration
@BindProps(prefix="solon.code.cli")
public class CliConfig {
    public String name;
    public String workDir = "./work";

    public int maxSteps = 30;
    public int sessionWindowSize = 10;


    public String webEndpoint = "/cli";
    public String acpTransport = "stdio";
    public String acpEndpoint = "/acp";

    public boolean enableConsole = true;
    public boolean enableWeb = false;
    public boolean enableAcp = false;
    public boolean enableHitl = false;

    public ChatConfig chatModel;
    public Map<String, String> mountPool;
}
