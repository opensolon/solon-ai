package org.noear.solon.ai.rag.splitter;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.noear.solon.ai.embedding.Embedding;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.embedding.EmbeddingOptions;
import org.noear.solon.ai.embedding.EmbeddingResponse;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentSplitter;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.SimilarityUtil;

import java.io.IOException;
import java.util.*;

/**
 * 基于语义的文本分割器
 *
 * <p>通过嵌入模型评估句子间的语义相似度，在语义边界处进行分割。
 * 算法参考 chonkie 的 SemanticChunker（不含 SkipWindow）。
 *
 * <p>算法流程：
 * <ol>
 *   <li>使用分隔符将文本拆分为句子</li>
 *   <li>使用滑动窗口计算每个位置的上下文嵌入向量</li>
 *   <li>计算窗口嵌入与下一个句子嵌入的余弦相似度</li>
 *   <li>在相似度低于阈值处标记为分割点</li>
 *   <li>按分割点将句子分组，确保每组不超过最大 Token 数</li>
 * </ol>
 *
 * @author 烧饵块
 * @since 3.10.1
 */
public class SemanticSplitter implements DocumentSplitter {
    /**
     * 英文句子分隔符
     */
    public static final String[] ENGLISH_DELIM = {". ", "! ", "? ", "\n"};
    /**
     * 中文句子分隔符
     */
    public static final String[] CHINESE_DELIM = {"。", "！", "？", "；", "\n"};
    /**
     * 中英文常用句子分隔符
     */
    public static final String[] ALL_COMMON_DELIM = {". ", "! ", "? ", "。", "！", "？", "；", "\n"};

    protected EmbeddingModel embeddingModel;

    private final double similarityThreshold;
    private final int maxChunkTokenSize;
    private final int similarityWindow;
    private final int minSentencesPerChunk;
    private final String[] delimiters;

    private EncodingRegistry encodingRegistry;
    private EncodingType encodingType;

    /**
     * 使用默认参数构造（阈值 0.5、最大 512 Token、窗口 3、最少 1 句、全部常用分隔符）
     *
     * @param embeddingModel 嵌入模型
     */
    public SemanticSplitter(EmbeddingModel embeddingModel) {
        this(embeddingModel, 0.5, 512, 3, 1, ALL_COMMON_DELIM);
    }

    /**
     * @param embeddingModel       嵌入模型
     * @param similarityThreshold  相似度阈值（0~1 之间，低于此值则在该位置分割）
     * @param maxChunkTokenSize    每个 chunk 最大 Token 数
     * @param similarityWindow     滑动窗口大小（用于构建上下文的句子数量）
     * @param minSentencesPerChunk 每个 chunk 最小句子数，如果确实很相似的话，不遵守该参数。
     * @param delimiters           句子分割字符数组
     */
    public SemanticSplitter(EmbeddingModel embeddingModel, double similarityThreshold,
                            int maxChunkTokenSize, int similarityWindow,
                            int minSentencesPerChunk, String[] delimiters) {
        if (embeddingModel == null) {
            throw new IllegalArgumentException("embeddingModel must not be null");
        }
        if (similarityThreshold <= 0 || similarityThreshold >= 1) {
            throw new IllegalArgumentException("similarityThreshold must be between 0 and 1 (exclusive)");
        }
        if (maxChunkTokenSize <= 0) {
            throw new IllegalArgumentException("maxChunkTokenSize must be positive");
        }
        if (similarityWindow <= 0) {
            throw new IllegalArgumentException("similarityWindow must be positive");
        }
        if (minSentencesPerChunk <= 0) {
            throw new IllegalArgumentException("minSentencesPerChunk must be positive");
        }
        if (delimiters == null || delimiters.length == 0) {
            throw new IllegalArgumentException("delimiters must not be empty");
        }

        this.embeddingModel = embeddingModel;
        this.similarityThreshold = similarityThreshold;
        this.maxChunkTokenSize = maxChunkTokenSize;
        this.similarityWindow = similarityWindow;
        this.minSentencesPerChunk = minSentencesPerChunk;
        this.delimiters = delimiters;
        this.encodingRegistry = Encodings.newLazyEncodingRegistry();
        this.encodingType = EncodingType.CL100K_BASE;
    }

    /**
     * 设置编码库
     */
    public void setEncodingRegistry(EncodingRegistry encodingRegistry) {
        if (encodingRegistry != null) {
            this.encodingRegistry = encodingRegistry;
        }
    }

    /**
     * 设置编码类型
     */
    public void setEncodingType(EncodingType encodingType) {
        if (encodingType != null) {
            this.encodingType = encodingType;
        }
    }

    @Override
    public List<Document> split(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            result.addAll(splitDocument(doc));
        }
        return result;
    }

    /**
     * 分割单个文档
     */
    protected List<Document> splitDocument(Document doc) {
        String text = doc.getContent();
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 拆分为句子
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return Collections.emptyList();
        }

        // 句子数不够窗口大小，直接作为单个 chunk 返回
        if (sentences.size() <= similarityWindow) {
            return Collections.singletonList(new Document(text, doc.getMetadata()));
        }

        try {
            // 2. 计算相邻句子间的语义相似度
            double[] similarities = computeSimilarities(sentences);

            // 3. 根据相似度找到分割点
            List<Integer> splitIndices = findSplitIndices(similarities);

            // 4. 按分割点将句子分组
            List<List<String>> groups = groupSentences(sentences, splitIndices);

            // 5. 拆分超过最大 Token 数的组
            groups = splitOversizedGroups(groups);

            // 6. 生成 Document 列表
            return createDocuments(groups, doc.getMetadata());
        } catch (IOException e) {
            throw new RuntimeException("Embedding failed during semantic splitting", e);
        }
    }

    /**
     * 按分隔符拆分句子，分隔符保留在前一个句子末尾
     */
    protected List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        int i = 0;

        while (i < text.length()) {
            String foundDelim = null;
            for (String delim : delimiters) {
                if (text.startsWith(delim, i)) {
                    foundDelim = delim;
                    break;
                }
            }

            if (foundDelim != null) {
                String sentence = text.substring(start, i + foundDelim.length());
                if (!sentence.trim().isEmpty()) {
                    sentences.add(sentence);
                }
                start = i + foundDelim.length();
                i = start;
            } else {
                i++;
            }
        }

        // 剩余文本作为最后一个句子
        if (start < text.length()) {
            String remaining = text.substring(start);
            if (!remaining.trim().isEmpty()) {
                sentences.add(remaining);
            }
        }

        return sentences;
    }

    /**
     * 计算滑动窗口与下一个句子之间的语义相似度
     *
     * <p>对于每个位置 i，将 sentences[i..i+window) 拼接为窗口文本，
     * 与 sentences[i+window] 分别计算嵌入后求余弦相似度。
     */
    private double[] computeSimilarities(List<String> sentences) throws IOException {
        int count = sentences.size() - similarityWindow;

        // 拼接全部需要嵌入的文本（前半窗口文本，后半下一句文本）
        List<String> allTexts = new ArrayList<>(count * 2);

        for (int i = 0; i < count; i++) {
            StringBuilder windowText = new StringBuilder();
            for (int j = i; j < i + similarityWindow; j++) {
                windowText.append(sentences.get(j));
            }
            allTexts.add(windowText.toString());
        }

        for (int i = 0; i < count; i++) {
            allTexts.add(sentences.get(i + similarityWindow));
        }

        // 批量嵌入
        List<float[]> allEmbeddings = batchEmbed(allTexts);

        // 计算余弦相似度
        double[] similarities = new double[count];
        for (int i = 0; i < count; i++) {
            similarities[i] = SimilarityUtil.cosineSimilarity(
                    allEmbeddings.get(i),
                    allEmbeddings.get(count + i)
            );
        }

        return similarities;
    }

    /**
     * 批量嵌入文本，按模型批次大小分批调用
     */
    private List<float[]> batchEmbed(List<String> texts) throws IOException {
        List<float[]> embeddings = new ArrayList<>(texts.size());
        int batchSize = embeddingModel.batchSize();
        List<List<String>> batches = ListUtil.partition(texts, batchSize);

        for (List<String> batch : batches) {
            EmbeddingResponse resp = embeddingModel.input(batch).options(new EmbeddingOptions().dimensions(512)).call();
            if (resp.getError() != null) {
                throw resp.getError();
            }
            for (Embedding emb : resp.getData()) {
                embeddings.add(emb.getEmbedding());
            }
        }

        return embeddings;
    }

    /**
     * 根据相似度数组找到分割点
     *
     * <p>相似度低于阈值的位置标记为分割点，同时保证相邻分割点间满足最小句子数约束。
     * 返回的索引为句子列表中的位置。
     */
    private List<Integer> findSplitIndices(double[] similarities) {
        List<Integer> splitIndices = new ArrayList<>();
        splitIndices.add(0);

        int lastSplitSentenceIdx = 0;

        for (int i = 0; i < similarities.length; i++) {
            int sentenceIdx = i + similarityWindow;

            if (similarities[i] < similarityThreshold) {
                // 确保与上一个分割点之间有足够的句子
                if (sentenceIdx - lastSplitSentenceIdx >= minSentencesPerChunk) {
                    splitIndices.add(sentenceIdx);
                    lastSplitSentenceIdx = sentenceIdx;
                }
            }
        }

        return splitIndices;
    }

    /**
     * 将句子按分割点索引分组
     */
    private List<List<String>> groupSentences(List<String> sentences, List<Integer> splitIndices) {
        List<List<String>> groups = new ArrayList<>();

        for (int i = 0; i < splitIndices.size(); i++) {
            int start = splitIndices.get(i);
            int end = (i + 1 < splitIndices.size()) ? splitIndices.get(i + 1) : sentences.size();

            if (start < end) {
                groups.add(new ArrayList<>(sentences.subList(start, end)));
            }
        }

        return groups;
    }

    /**
     * 将超过最大 Token 数的组拆分为多个子组
     */
    private List<List<String>> splitOversizedGroups(List<List<String>> groups) {
        Encoding encoding = encodingRegistry.getEncoding(encodingType);
        List<List<String>> result = new ArrayList<>();

        for (List<String> group : groups) {
            int totalTokens = 0;
            for (String sentence : group) {
                totalTokens += encoding.encode(sentence).size();
            }

            if (totalTokens <= maxChunkTokenSize) {
                result.add(group);
            } else {
                // 逐句分配到子组
                List<String> currentGroup = new ArrayList<>();
                int currentTokens = 0;

                for (String sentence : group) {
                    int sentenceTokens = encoding.encode(sentence).size();

                    if (currentTokens + sentenceTokens <= maxChunkTokenSize) {
                        currentGroup.add(sentence);
                        currentTokens += sentenceTokens;
                    } else {
                        if (!currentGroup.isEmpty()) {
                            result.add(currentGroup);
                        }
                        currentGroup = new ArrayList<>();
                        currentGroup.add(sentence);
                        currentTokens = sentenceTokens;
                    }
                }

                if (!currentGroup.isEmpty()) {
                    result.add(currentGroup);
                }
            }
        }

        return result;
    }

    /**
     * 从句子组创建 Document 列表
     */
    private List<Document> createDocuments(List<List<String>> groups, Map<String, Object> metadata) {
        List<Document> documents = new ArrayList<>(groups.size());

        for (List<String> group : groups) {
            StringBuilder sb = new StringBuilder();
            for (String sentence : group) {
                sb.append(sentence);
            }

            String content = sb.toString();
            if (!content.trim().isEmpty()) {
                documents.add(new Document(content, metadata));
            }
        }

        return documents;
    }
}
