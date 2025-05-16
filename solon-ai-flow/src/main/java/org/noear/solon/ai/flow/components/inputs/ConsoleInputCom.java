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
package org.noear.solon.ai.flow.components.inputs;

import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 控制台输入组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ConsoleInput")
public class ConsoleInputCom extends AbsAiComponent implements AiIoComponent {
    @Override
    public Object getInput(FlowContext context, Node node) throws Throwable {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        return reader.readLine();
    }

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node);

        setOutput(context, node, data);
    }
}