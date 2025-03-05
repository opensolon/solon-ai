package org.noear.solon.ai.rag.loader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.sl.draw.geom.GuideIf.Op;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.core.util.SupplierEx;

public class WordLoader extends AbstractOptionsDocumentLoader<WordLoader.Options, WordLoader> {
    private final SupplierEx<InputStream> source;

    public WordLoader(File file) {
        this(() -> new FileInputStream(file));
    }

    public WordLoader(URL url) {
        this(() -> url.openStream());
    }

    public WordLoader(SupplierEx<InputStream> source) {
        this.source = source;
        this.options = new Options();
    }

    @Override
    public List<Document> load() throws IOException {
        List<Document> documents = new ArrayList<>();
        try (InputStream stream = source.get()) {
            try (
                    XWPFDocument reader = new XWPFDocument(stream)) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("type", "word");
                // 读取文档内容
                if (options.loadMode == LoadMode.SINGLE) {
                    XWPFWordExtractor extractor = new XWPFWordExtractor(reader);
                    // 一次性获取文档的全部文本内容
                    String content = extractor.getText();
                    Document doc = new Document(content, metadata)
                            .metadata(this.additionalMetadata);
                    documents.add(doc);
                    extractor.close();
                } else {
                    /* 
                    for (XWPFParagraph extractor : reader.getParagraphs()) {
                        String content = extractor.getText();
                        Document doc = new Document(content, metadata)
                                .metadata(this.additionalMetadata);
                        documents.add(doc);
                    }
                    */
                    XWPFWordExtractor extractor = new XWPFWordExtractor(reader);
                    String content = extractor.getText();
                    Integer pageSize = this.options.pageSize;
                    int pageCount = (int) Math.ceil(content.length() / (double)pageSize);
                    for (int i = 0; i < pageCount; i++) {
                        String pageContent = content.substring(i * pageSize, Math.min((i + 1) * pageSize, content.length()));
                        Document doc = new Document(pageContent, metadata)
                               .metadata(this.additionalMetadata);
                        documents.add(doc);;
                    }
                    
                    extractor.close();
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        return documents;
    }

    public static enum LoadMode {
        /**
         * 整个文档作为一个 Document
         */
        SINGLE,
        /**
         * 每页作为一个 Document
         */
        PAGE
    }

    /**
     * 选项
     */
    public static class Options {
        private LoadMode loadMode = LoadMode.PAGE;
        private Integer pageSize = 500;


        /**
         * WORD 加载模式，可以是单文档模式或分页模式
         */
        public Options loadMode(LoadMode loadMode) {
            this.loadMode = loadMode;
            return this;
        }

        public Options pageSize(Integer pageSize) {
            this.pageSize = pageSize;
            return this;
        }
        
    }
}
