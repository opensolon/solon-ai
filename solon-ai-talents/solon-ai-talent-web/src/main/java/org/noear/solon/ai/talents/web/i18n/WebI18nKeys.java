/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.web.i18n;

import org.noear.solon.lang.Preview;

/**
 * web 模块的文案 key（常量名 = key 转大写，"." 换成 "_"）
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class WebI18nKeys {
    private WebI18nKeys() {
    }

    // ---- CodeSearchTalent ----
    public static final String CODESEARCH_TIMEOUT = "codesearch.timeout";
    /**
     * 变量：error
     */
    public static final String CODESEARCH_ERROR = "codesearch.error";
    public static final String CODESEARCH_EMPTY = "codesearch.empty";

    // ---- WebSearchDriverTalent ----
    public static final String SEARCH_EMPTY = "search.empty";
    /**
     * 变量：error
     */
    public static final String SEARCH_FAIL = "search.fail";

    // ---- WebCrawlerDriverTalent ----
    public static final String CRAWL_INVALID_URL = "crawl.invalid_url";
    /**
     * 变量：error
     */
    public static final String CRAWL_FAIL = "crawl.fail";
}
