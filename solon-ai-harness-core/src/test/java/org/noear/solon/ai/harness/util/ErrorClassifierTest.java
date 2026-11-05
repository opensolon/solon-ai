/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 异常分类器测试（loop 与 goal 共用）。
 */
class ErrorClassifierTest {

    @Test
    void errorClassification() {
        assertEquals("SSL", ErrorClassifier.classifyError(new RuntimeException("PKIX path building failed")));
        assertEquals("NETWORK", ErrorClassifier.classifyError(new RuntimeException("Connection refused")));
        assertEquals("HTTP_4XX", ErrorClassifier.classifyError(new RuntimeException("status code: 404")));
        assertEquals("OTHER", ErrorClassifier.classifyError(new RuntimeException("boo")));
    }

    @Test
    void causeChainIsIncluded() {
        RuntimeException root = new RuntimeException("Connection reset by peer");
        RuntimeException wrapped = new RuntimeException("invoke failed", root);
        assertEquals("NETWORK", ErrorClassifier.classifyError(wrapped));
    }
}
