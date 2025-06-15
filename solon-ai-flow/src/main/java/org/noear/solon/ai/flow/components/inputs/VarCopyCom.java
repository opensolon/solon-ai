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
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 变量复制组件
 *
 * @author noear
 * @since 3.3
 */
@Component("VarCopy")
public class VarCopyCom extends AbsAiComponent implements AiIoComponent {
    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        //用元信息，复制上下文变量
        node.getMetas().forEach((newKey, oldKey) -> {
            Object oldVal = context.get(String.valueOf(oldKey));
            if (oldVal != null) {
                context.put(newKey, oldVal);
            }
        });
    }
}