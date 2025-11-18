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
package org.noear.solon.ai.flow.components;

/**
 * 组件属性
 *
 * @author noear
 * @since 3.3
 */
public interface Attrs {
    String META_INPUT = "input";
    String META_OUTPUT = "output";
    String META_ATTACHMENT = "attachment";

    String CTX_CHAT_SESSION = "chatSession";
    String CTX_PROPERTY = "property";
    String CTX_MESSAGE = "message";
    String CTX_ATTACHMENT = "attachment";

    String PROP_REPOSITORY = "repository";

    String PROP_EMBEDDING_MODEL = "embeddingModel";
    String PROP_CHAT_MODEL = "chatModel";
    String PROP_IMAGE_MODEL = "imageModel";
    String PROP_GENERATE_MODEL = "generateModel";
}