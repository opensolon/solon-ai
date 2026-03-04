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
package features.ai.skills.lucene;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.skills.lucene.LuceneSkill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LuceneSkill 单测 (Java 8 兼容版)
 */
class LuceneSkillTest {

    @TempDir
    Path tempDir;

    private LuceneSkill luceneSkill;

    @BeforeEach
    void setUp() throws IOException {
        // 在临时目录中准备测试文件
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);

        // 1. 正常的 Java 文件 (Java 8 使用 Files.write)
        String javaContent = "package com.test; \n public class UserService { \n public void login() { } \n }";
        Files.write(src.resolve("UserService.java"), javaContent.getBytes(StandardCharsets.UTF_8));

        // 2. 配置文件
        String xmlContent = "<config><timeout>3000</timeout></config>";
        Files.write(src.resolve("config.xml"), xmlContent.getBytes(StandardCharsets.UTF_8));

        // 3. 应该被忽略的目录
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.write(gitDir.resolve("config"), "ignore this file".getBytes(StandardCharsets.UTF_8));

        // 4. 不在后缀列表中的文件
        Files.write(tempDir.resolve("image.png"), "fake binary data".getBytes(StandardCharsets.UTF_8));

        luceneSkill = new LuceneSkill(tempDir.toString());
    }

    @Test
    void testRefreshSearchIndexAndSearch() {
        // 执行索引刷新
        String refreshResult = luceneSkill.refreshSearchIndex();
        assertTrue(refreshResult.contains("索引刷新完成"), "索引刷新应成功");

        // 测试搜索存在的代码片段
        String searchResult = luceneSkill.full_text_search("login");
        assertTrue(searchResult.contains("UserService.java"), "应能搜索到 UserService 类");
        assertTrue(searchResult.contains("public void login"), "搜索结果预览应包含关键字内容");

        // 测试搜索配置文件
        String xmlResult = luceneSkill.full_text_search("timeout");
        assertTrue(xmlResult.contains("config.xml"), "应能搜索到 XML 配置文件内容");
    }

    @Test
    void testIgnoreLogic() {
        luceneSkill.refreshSearchIndex();

        // 搜索 .git 目录下的内容，理应搜索不到
        String result = luceneSkill.full_text_search("ignore");
        assertTrue(result.contains("未找到匹配内容"), "不应索引被忽略的目录内容");
    }

    @Test
    void testExtensionFilter() {
        luceneSkill.refreshSearchIndex();

        // 搜索 .png 内容（默认不支持），理应搜索不到
        String result = luceneSkill.full_text_search("binary");
        assertTrue(result.contains("未找到匹配内容"), "不应索引未定义的后缀名文件");
    }

    @Test
    void testCustomConfiguration() throws IOException {
        // 自定义配置：仅支持 .png，不忽略任何目录
        luceneSkill.searchableExtensions(Arrays.asList("png"))
                .ignoreNames(Collections.emptyList());

        luceneSkill.refreshSearchIndex();

        // 现在应该能搜到图片文件的内容了
        String result = luceneSkill.full_text_search("binary");
        assertTrue(result.contains("image.png"), "自定义后缀配置应生效");
    }

    @Test
    void testSpecialCharacters() {
        luceneSkill.refreshSearchIndex();

        // 验证 QueryParser.escape 是否生效
        // 括号、空格等特殊字符不应导致解析器抛出异常
        assertDoesNotThrow(() -> {
            String result = luceneSkill.full_text_search("public void login()");
            assertNotNull(result);
        });
    }

    @Test
    void testEmptyDirectory(@TempDir Path emptyDir) {
        LuceneSkill emptySkill = new LuceneSkill(emptyDir.toString());
        emptySkill.refreshSearchIndex();
        String result = emptySkill.full_text_search("anything");
        assertTrue(result.contains("未找到匹配内容"), "空目录搜索应优雅处理");
    }
}