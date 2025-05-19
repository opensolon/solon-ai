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
package org.noear.solon.ai.flow.components.outputs;

import org.noear.solon.Utils;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.annotation.Component;
import org.noear.solon.expression.snel.SnEL;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 控制台输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ConsoleOutput")
public class ConsoleOutputCom extends VarOutputCom implements AiIoComponent {
    //私有元信息
    static final String META_FORMAT = "format";

    @Override
    public void setOutput(FlowContext context, Node node, Object data) throws Throwable {
        super.setOutput(context, node, data);

        //格式化输出
        String format = node.getMeta(META_FORMAT);
        if (Utils.isEmpty(format)) {
            System.out.println(data);
        } else {
            String formatted = SnEL.evalTmpl(format, context.model());
            System.out.println(formatted);
        }
    }
}