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
package org.noear.solon.ai.generate.dialect;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.generate.GenerateConfig;
import org.noear.solon.ai.generate.GenerateOptions;

import java.util.Map;

/**
 * 生成模型方言虚拟类
 *
 * @author noear
 * @since 3.1
 */
public abstract class AbstractGenerateDialect implements GenerateDialect {
    @Override
    public String buildRequestJson(GenerateConfig config, GenerateOptions options, String promptStr, Map promptMap) {
        return new ONode().build(n -> {
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            if (Utils.isNotEmpty(promptStr)) {
                //文本形态
                n.set("prompt", promptStr);
            } else if (Utils.isNotEmpty(promptMap)) {
                //字典形态
                n.setAll(ONode.load(promptMap));
            }

            for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                n.set(kv.getKey(), kv.getValue());
            }
        }).toJson();
    }
}
