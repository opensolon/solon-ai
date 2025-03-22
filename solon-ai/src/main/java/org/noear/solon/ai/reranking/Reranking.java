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
package org.noear.solon.ai.reranking;

/**
 * 重排数据
 *
 * @author noear
 * @since 3.1
 */
public class Reranking implements Comparable<Reranking> {
    private int index;
    private String text;
    private float relevance_score;

    public Reranking() {
        //用于反序列化
    }

    public Reranking(int index, String text, float relevance_score) {
        this.text = text;
        this.index = index;
        this.relevance_score = relevance_score;
    }

    public int getIndex() {
        return index;
    }

    public String getText() {
        return text;
    }

    public float getRelevanceScore() {
        return relevance_score;
    }

    @Override
    public String toString() {
        return "{" +
                "index=" + index +
                ", text='" + text + "'" +
                ", relevance_score=" + relevance_score +
                '}';
    }

    @Override
    public int compareTo(Reranking o) {
        if (this.index < o.index) {
            return -1;
        } else if (this.index > o.index) {
            return 1;
        } else {
            return 0;
        }
    }
}