package org.noear.solon.ai.chat.tool;

import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.TextBlock;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolResult implements Serializable {
    private final List<ContentBlock> blocks = new ArrayList<>();
    private String text;
    private boolean isError;
    private Map<String, Object> metadata;

    public ToolResult() {
        //用于反序列化
    }

    public ToolResult(String text) {
        addBlock(TextBlock.of(false, text));
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

    /**
     * 添加内容块（图像、音频、视频）
     */
    public ToolResult addBlock(ContentBlock media) {
        if (media instanceof TextBlock) {
            text = ((TextBlock) media).getContent();
        }

        this.blocks.add(media);
        return this;
    }

    public String getContent() {
        if (text != null) {
            return text;
        }

        return null;
    }

    /**
     * 获取所有内容块
     */
    public List<ContentBlock> getBlocks() {
        return blocks;
    }

    /**
     * 是否有多媒体
     */
    public boolean isMultiModal() {
        int size = blocks.size();
        if (size > 1) {
            return true;
        }

        if (size == 1) {
            return !(blocks.get(0) instanceof TextBlock);
        }

        return false;
    }

    /**
     * 是否为错误响应
     */
    public boolean isError() {
        return isError;
    }

    public void setError(boolean error) {
        isError = error;
    }

    /**
     * 元信息
     */
    public Map<String, Object> getMetadata() {
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
        return metadata;
    }

    /**
     * 简单的快捷获取文本方法（用于非多模态场景降级）
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                sb.append(((TextBlock) block).getContent());
            } else {
                sb.append("[Media: ").append(block.getMimeType()).append("]");
            }
        }
        return sb.toString();
    }
}