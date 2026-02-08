package org.noear.solon.ai.chat.tool;

import org.noear.solon.ai.AiMedia;
import org.noear.solon.ai.media.Text;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolResult implements Serializable {
    private final List<AiMedia> medias = new ArrayList<>();
    private String text;
    private int testCount;
    private boolean isError;
    private Map<String, Object> metadata;

    public ToolResult() {
        //用于反序列化
    }

    public ToolResult(String text) {
        this.addText(text);
    }

    public static boolean isEmpty(ToolResult result) {
        return result == null || result.getMedias().isEmpty();
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
     * 添加媒体块（图像、音频、视频）
     */
    public ToolResult addText(String text) {
       addMedia(Text.of(false, text));
        return this;
    }

    /**
     * 添加媒体块（图像、音频、视频）
     */
    public ToolResult addMedia(AiMedia media) {
        if (media instanceof Text) {
            text = ((Text) media).getContent();
            testCount++;
        }

        this.medias.add(media);
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
    public List<AiMedia> getMedias() {
        return medias;
    }

    public boolean hasMedias(){
        return medias.size() > testCount;
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
        for (AiMedia media : medias) {
            if (media instanceof Text) {
                sb.append(((Text) media).getContent());
            } else {
                sb.append("[Media: ").append(media.getMimeType()).append("]");
            }
        }
        return sb.toString();
    }
}