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
package org.noear.solon.ai.image.dialect;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.image.ImageConfig;
import org.noear.solon.ai.image.ImageOptions;

import java.util.Map;

/**
 * 图像模型方言虚拟类
 *
 * @author noear
 * @since 3.1
 */
public abstract class AbstractImageDialect implements ImageDialect {
    @Override
    public String buildRequestJson(ImageConfig config, ImageOptions options, String promptStr, Map promptMap) {
        return new ONode().then(n -> {
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            if (Utils.isNotEmpty(promptStr)) {
                //文本形态
                n.set("prompt", promptStr);
            } else if (Utils.isNotEmpty(promptMap)) {
                //字典形态
                n.setAll(ONode.ofBean(promptMap).getObject());
            }

            for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                n.set(kv.getKey(), kv.getValue());
            }
        }).toJson();
    }
}
