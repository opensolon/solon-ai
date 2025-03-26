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

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.EmptyFileException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.noear.solon.Utils;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.core.util.SupplierEx;
import org.noear.solon.lang.Preview;

/**
 * Word 文档加载器
 * <p>
 * 读取Word内容，并转换为文本
 * </p>
 *
 * @author linziguan
 * @since 3.1
 */
@Preview("3.1")
public class WordLoader extends AbstractOptionsDocumentLoader<WordLoader.Options, WordLoader> {
    private final SupplierEx<InputStream> source;

    public WordLoader(File source) {
        this(() -> new FileInputStream(source));
    }

    public WordLoader(URL source) {
        this(() -> source.openStream());
    }

    public WordLoader(SupplierEx<InputStream> source) {
        this.source = source;
        this.options = new Options();
        this.additionalMetadata.put("type", "word");
    }

    @Override
    public List<Document> load() throws IOException {
        try (InputStream stream = source.get()) {
            List<Document> documents = new ArrayList<>();

            try (Closeable reader = create(stream)) {
                if (reader instanceof XWPFDocument) {
                    doRead((XWPFDocument) reader, documents);
                } else {
                    doRead((HWPFDocument) reader, documents);
                }
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

    private void doRead(XWPFDocument reader, List<Document> documents) throws IOException {
        if (options.loadMode == LoadMode.SINGLE) {
            try (XWPFWordExtractor extractor = new XWPFWordExtractor(reader)) {
                // 一次性获取文档的全部文本内容
                String content = extractor.getText();

                Document doc = new Document(content)
                        .metadata(this.additionalMetadata);
                documents.add(doc);
            }
        } else {
            for (XWPFParagraph extractor : reader.getParagraphs()) {
                String content = extractor.getText();

                Document doc = new Document(content)
                        .metadata(this.additionalMetadata);
                documents.add(doc);
            }
        }
    }

    private void doRead(HWPFDocument reader, List<Document> documents) throws IOException {
        if (options.loadMode == LoadMode.SINGLE) {
            // 一次性获取文档的全部文本内容
            String content = reader.getDocumentText().trim();

            if (Utils.isNotEmpty(content)) {
                Document doc = new Document(content)
                        .metadata(this.additionalMetadata);
                documents.add(doc);
            }
        } else {
            Range range = reader.getRange(); // 获取文档范围
            int numParagraphs = range.numParagraphs(); // 获取段落数量

            for (int i = 0; i < numParagraphs; i++) {
                Paragraph paragraph = range.getParagraph(i); // 获取段落
                String content = paragraph.text().trim();

                if (Utils.isNotEmpty(content)) {
                    Document doc = new Document(content)
                            .metadata(this.additionalMetadata);
                    documents.add(doc);
                }
            }
        }
    }

    private Closeable create(InputStream inp) throws IOException, EncryptedDocumentException {
        InputStream is = FileMagic.prepareToCheckMagic(inp);
        byte[] emptyFileCheck = new byte[1];
        is.mark(emptyFileCheck.length);
        if (is.read(emptyFileCheck) < emptyFileCheck.length) {
            throw new EmptyFileException();
        } else {
            is.reset();
            FileMagic fm = FileMagic.valueOf(is);
            if (FileMagic.OOXML == fm) {
                return new XWPFDocument(inp);
            } else if (FileMagic.OLE2 != fm) {
                throw new IOException("Can't open word - unsupported file type: " + fm);
            } else {
                return new HWPFDocument(inp);
            }
        }
    }

    public static enum LoadMode {
        /**
         * 整个文档作为一个 Document
         */
        SINGLE,
        /**
         * 每段作为一个 Document
         */
        PARAGRAPH
    }

    /**
     * 选项
     */
    public static class Options {
        private LoadMode loadMode = LoadMode.PARAGRAPH;

        /**
         * WORD 加载模式，可以是单文档模式或分页模式
         */
        public Options loadMode(LoadMode loadMode) {
            this.loadMode = loadMode;
            return this;
        }
    }
}