package features.ai.core;

import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.embedding.Embedding;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingException;
import org.noear.solon.ai.embedding.EmbeddingResponse;
import org.noear.solon.ai.embedding.dialect.AbstractEmbeddingDialect;

import java.util.List;

/**
 * 测试用 OpenAI 兼容方言（避免 solon-ai-core 与 dialect 模块循环依赖）
 *
 * @author 烧饵块
 * @since 3.10.1
 */
class TestOpenaiEmbeddingDialect extends AbstractEmbeddingDialect {

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public boolean matched(EmbeddingConfig config) {
        return false;
    }

    @Override
    public EmbeddingResponse parseResponseJson(EmbeddingConfig config, String respJson) {
        ONode oResp = ONode.ofJson(respJson);
        String model = oResp.get("model").getString();

        if (oResp.hasKey("error")) {
            return new EmbeddingResponse(model,
                    new EmbeddingException(oResp.get("error").getString()), null, null);
        }

        List<Embedding> data = oResp.get("data").toBean(new TypeRef<List<Embedding>>() {
        });

        AiUsage usage = null;
        if (oResp.hasKey("usage")) {
            ONode oUsage = oResp.get("usage");
            long promptTokens = oUsage.get("prompt_tokens").getLong();
            long totalTokens = oUsage.get("total_tokens").getLong();
            usage = new AiUsage(promptTokens, 0L, 0L, totalTokens, oUsage);
        }

        return new EmbeddingResponse(model, null, data, usage);
    }
}
