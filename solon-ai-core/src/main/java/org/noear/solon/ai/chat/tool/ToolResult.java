package org.noear.solon.ai.chat.tool;

import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.Contents;

import java.util.Collection;

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
    public ToolResult addBlock(ContentBlock block) {
        return (ToolResult) super.addBlock(block);
    }

    @Override
    public ToolResult addBlocks(Collection<ContentBlock> blocks) {
        return (ToolResult) super.addBlocks(blocks);
    }
}