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
package org.noear.solon.ai.rag.loader;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.core.util.SupplierEx;
import org.noear.solon.lang.Preview;

/**
 * ppt 文档加载器
 * <p>读取ppt内容，并转换为文本</p>
 *
 * @author linziguan
 * @since 3.1
 */
@Preview("3.1")
public class PptLoader extends AbstractOptionsDocumentLoader<PptLoader.Options, PptLoader> {
    private final SupplierEx<InputStream> source;

    public PptLoader(SupplierEx<InputStream> source) {
        this.source = source;
        this.options = new Options();
        this.additionalMetadata.put("type", "ppt");
    }

    public PptLoader(File source) {
        this(() -> new FileInputStream(source));
    }

    public PptLoader(URL source) {
        this(() -> source.openStream());
    }


    @Override
    public List<Document> load() throws IOException {
        // 实现PPT文档的加载逻辑
        try (InputStream stream = source.get()) {
            Map<String, Object> metadata = new HashMap<>();

            List<Document> documents = new ArrayList<>();
            // 读取PPT内容并转换为文本
            BodyContentHandler handler = new BodyContentHandler();
            Metadata docMetadata = new Metadata();
            ParseContext context = new ParseContext();

            new AutoDetectParser().parse(stream, handler, docMetadata, context);

            String content = handler.toString();

            if (this.options.loadMode == LoadMode.PAGE) {
                for (String pageText : content.split(options.pageDelimiter)) {
                    Document document = new Document(pageText, metadata).metadata(this.additionalMetadata);
                    documents.add(document);
                }
            } else {
                Document document = new Document(content, metadata).metadata(this.additionalMetadata);
                documents.add(document);
            }

            return documents;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
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

    public static class Options {
        private LoadMode loadMode = LoadMode.SINGLE;
        private String pageDelimiter = "\n\n\n";

        public Options pageDelimiter(String pageDelimiter) {
            this.pageDelimiter = pageDelimiter;
            return this;
        }

        public Options loadMode(LoadMode loadMode) {
            this.loadMode = loadMode;
            return this;
        }
    }
}