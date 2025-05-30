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
package org.noear.solon.ai.rag.splitter;

import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentSplitter;

import java.util.*;

/**
 * 管道分割器
 *
 * @author noear
 * @since 3.1
 */
public class SplitterPipeline implements DocumentSplitter {
    private Deque<DocumentSplitter> pipeline = new LinkedList<>();

    /**
     * 添加到后面
     */
    public SplitterPipeline next(DocumentSplitter splitter) {
        if (splitter != null) {
            pipeline.addLast(splitter);
        }

        return this;
    }

    /**
     * 添加到后面
     */
    public SplitterPipeline next(List<DocumentSplitter> splitters) {
        if (splitters != null) {
            for (DocumentSplitter splitter : splitters) {
                pipeline.addLast(splitter);
            }
        }

        return this;
    }

    /**
     * 分割
     */
    @Override
    public List<Document> split(List<Document> documents) {
        for (DocumentSplitter splitter : pipeline) {
            documents = splitter.split(documents);
        }

        return documents;
    }
}