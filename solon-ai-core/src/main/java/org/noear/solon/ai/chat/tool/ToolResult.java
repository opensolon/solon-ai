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
package org.noear.solon.ai.chat.tool;

import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.Contents;

import java.util.Collection;

/**
 * 工具调用结果
 *
 * @author noear
 * @since 3.9.2
 */
public class ToolResult extends Contents {
    public ToolResult() {
        //用于反序列化
        super();
    }

    public ToolResult(String text) {
        super(text);
    }

    public static boolean isEmpty(ToolResult result) {
        return result == null || result.getBlocks().isEmpty();
    }

    /**
     * 静态构造：成功结果
     */
    public static ToolResult success(String text) {
        return new ToolResult(text);
    }

    /**
     * 静态构造：错误结果
     */
    public static ToolResult error(String errorMessage) {
        ToolResult result = new ToolResult(errorMessage);
        result.isError = true;
        return result;
    }

    @Override
    public ToolResult addText(String text) {
        return (ToolResult) super.addText(text);
    }

    @Override
    public ToolResult addBlock(ContentBlock block) {
        return (ToolResult) super.addBlock(block);
    }

    @Override
    public ToolResult addBlocks(Collection<ContentBlock> blocks) {
        return (ToolResult) super.addBlocks(blocks);
    }
}