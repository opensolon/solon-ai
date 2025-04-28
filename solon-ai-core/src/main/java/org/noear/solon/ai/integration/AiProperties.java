package org.noear.solon.ai.integration;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.reranking.RerankingConfig;
import org.noear.solon.annotation.BindProps;

import java.util.Map;

/**
 * Ai 属性（仅用于配置提示）
 *
 * @author noear
 * @since 3.1
 */
@BindProps(prefix = "solon.ai")
public class AiProperties {
    /**
     * 聊天模型配置
     */
    private Map<String, ChatConfig> chat;
    /**
     * 图像模型配置
     */
    private Map<String, ChatConfig> image;
    /**
     * 嵌入模型配置
     */
    private Map<String, EmbeddingConfig> embed;
    /**
     * 重排模型配置
     */
    private Map<String, RerankingConfig> rerank;

    public Map<String, ChatConfig> getChat() {
        return chat;
    }

    public void setChat(Map<String, ChatConfig> chat) {
        this.chat = chat;
    }

    public Map<String, ChatConfig> getImage() {
        return image;
    }

    public void setImage(Map<String, ChatConfig> image) {
        this.image = image;
    }

    public Map<String, EmbeddingConfig> getEmbed() {
        return embed;
    }

    public void setEmbed(Map<String, EmbeddingConfig> embed) {
        this.embed = embed;
    }

    public Map<String, RerankingConfig> getRerank() {
        return rerank;
    }

    public void setRerank(Map<String, RerankingConfig> rerank) {
        this.rerank = rerank;
    }
}