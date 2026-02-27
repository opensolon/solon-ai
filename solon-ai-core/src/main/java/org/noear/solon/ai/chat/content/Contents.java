package org.noear.solon.ai.chat.content;

import org.noear.solon.Utils;

import java.io.Serializable;
import java.util.*;

/**
 *
 * @author noear
 * @since 3.9.2
 */
public class Contents implements Serializable {
    protected final List<ContentBlock> blocks = new ArrayList<>();
    protected String text;
    protected boolean isError;
    protected Map<String, Object> metas;

    public Contents() {
        //用于反序列化
    }

    public Contents(String text) {
        addText(text);
    }

    /**
     * 添加文本块
     */
    public Contents addText(String text) {
        return addBlock(TextBlock.of(text));
    }

    /**
     * 添加内容块（图像、音频、视频）
     */
    public Contents addBlock(ContentBlock block) {
        if (block instanceof TextBlock) {
            if (Utils.isEmpty(text)) {
                text = block.getContent();
            }
        }

        this.blocks.add(block);
        return this;
    }

    public Contents addBlocks(Collection<ContentBlock> blocks) {
        if (blocks == null) {
            return this;
        }

        for (ContentBlock block : blocks) {
            addBlock(block);
        }

        return this;
    }


    /**
     * 单模态内容
     *
     */
    public String getContent() {
        if (text != null) {
            return text;
        }

        return null;
    }

    /**
     * 多模态内容
     */
    public List<ContentBlock> getBlocks() {
        return blocks;
    }


    public int size() {
        return blocks.size();
    }

    /**
     * 是否为多模态
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
    public Map<String, Object> metas() {
        if (metas == null) {
            metas = new LinkedHashMap<>();
        }
        return metas;
    }

    /**
     * 简单的快捷获取文本方法（用于非多模态场景降级）
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock) {
                sb.append(block.getContent());
            } else {
                sb.append("[Media: ").append(block.getMimeType()).append("]");
            }
        }
        return sb.toString();
    }
}
