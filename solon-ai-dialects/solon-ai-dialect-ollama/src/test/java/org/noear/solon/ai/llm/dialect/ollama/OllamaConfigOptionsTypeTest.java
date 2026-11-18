/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.llm.dialect.ollama;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.decision.DecisionConfig;
import org.noear.solon.ai.decision.DecisionModel;
import org.noear.solon.ai.decision.DecisionPredicateQuestion;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.generate.GenerateConfig;
import org.noear.solon.ai.generate.GenerateModel;
import org.noear.solon.core.Props;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置来源的选项（Solon 读出来都是字符串，含嵌套的 options）在发出的 http 请求体里是强类型
 *
 * <p>对应 issue IKW7WQ：{@code defaultOptions.options.num_ctx} 原来以字符串发出，Ollama 报
 * {@code option "num_ctx" must be of type integer}。走完整请求链路，抓取实际发出的请求体。</p>
 *
 * @author noear
 */
public class OllamaConfigOptionsTypeTest {
    private HttpServer server;
    private volatile String requestBody;
    private volatile String responseBody;

    @BeforeEach
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                }
                requestBody = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }

            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static Props props(String... kvs) {
        Props props = new Props();
        props.put("provider", "ollama");
        for (int i = 0; i < kvs.length; i += 2) {
            props.put(kvs[i], kvs[i + 1]);
        }
        return props;
    }

    @Test
    public void embeddingNestedOptionsAreTyped() throws Exception {
        responseBody = "{\"model\":\"bge-m3\",\"embeddings\":[[0.1,0.2]]}";

        EmbeddingConfig config = props(
                "apiUrl", url("/api/embed"),
                "model", "bge-m3",
                "defaultOptions.dimensions", "1024",
                "defaultOptions.options.num_ctx", "8192").bindTo(new EmbeddingConfig());

        EmbeddingModel.of(config).build().input("hello").call();

        ONode node = ONode.ofJson(requestBody);
        assertTrue(node.get("dimensions").isNumber(), requestBody);
        assertEquals(1024, node.get("dimensions").getInt());
        assertTrue(node.get("options").get("num_ctx").isNumber(), requestBody);
        assertEquals(8192, node.get("options").get("num_ctx").getInt());

        //配置载体不变（转换只发生在发请求时）
        assertEquals("1024", config.getModelOptions().option("dimensions"));
        assertEquals("8192", ((Map) config.getModelOptions().option("options")).get("num_ctx"));
    }

    @Test
    public void chatNestedOptionsAreTyped() throws Exception {
        responseBody = "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}";

        ChatConfig config = props(
                "apiUrl", url("/api/chat"),
                "model", "qwen3:8b",
                "defaultOptions.options.num_ctx", "8192",
                "defaultOptions.options.temperature", "0.2",
                "defaultOptions.keep_alive", "5m").bindTo(new ChatConfig());

        ChatModel.of(config).build().prompt("hi")
                //代码里设置的字符串值也一样（兼容 optionSet("xxx", "123") 的写法）
                .options(o -> o.optionSet("user", "10086").optionSet("seed", "12345678901"))
                .call();

        ONode node = ONode.ofJson(requestBody);
        assertTrue(node.get("options").get("num_ctx").isNumber(), requestBody);
        assertEquals(8192, node.get("options").get("num_ctx").getInt());
        assertEquals(0.2D, node.get("options").get("temperature").getDouble(), 0.0001D);
        assertEquals("5m", node.get("keep_alive").getString());
        assertEquals(12345678901L, node.get("seed").getLong());
        assertTrue(node.get("user").isString(), requestBody);
        assertEquals("10086", node.get("user").getString());

        //配置载体不变
        assertEquals("8192", ((Map) config.getModelOptions().option("options")).get("num_ctx"));
    }

    @Test
    public void generateNestedOptionsAreTyped() throws Exception {
        responseBody = "{\"model\":\"x\",\"response\":\"ok\",\"done\":true}";

        GenerateConfig config = props(
                "apiUrl", url("/api/generate"),
                "model", "x",
                "defaultOptions.options.num_ctx", "4096").bindTo(new GenerateConfig());

        GenerateModel.of(config).build().prompt("hi").call();

        ONode node = ONode.ofJson(requestBody);
        assertTrue(node.get("options").get("num_ctx").isNumber(), requestBody);
        assertEquals(4096, node.get("options").get("num_ctx").getInt());

        //配置载体不变
        assertEquals("4096", ((Map) config.getModelOptions().option("options")).get("num_ctx"));
    }

    /**
     * 流式调用是 toRequestData 的另一个调用方，同样还原
     */
    @Test
    public void chatStreamNestedOptionsAreTyped() throws Exception {
        responseBody = "{\"model\":\"x\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":false}\n"
                + "{\"model\":\"x\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}\n";

        ChatConfig config = props(
                "apiUrl", url("/api/chat"),
                "model", "x",
                "defaultOptions.options.num_ctx", "8192").bindTo(new ChatConfig());

        ChatModel.of(config).build().prompt("hi").stream().collectList().block();

        ONode node = ONode.ofJson(requestBody);
        assertTrue(node.get("stream").getBoolean(), requestBody);
        assertTrue(node.get("options").get("num_ctx").isNumber(), requestBody);
        assertEquals(8192, node.get("options").get("num_ctx").getInt());
    }

    /**
     * 方言在写 JSON 之前按类型读取选项（Ollama 只在 thinking 为 Boolean 时映射为原生 think），
     * 所以转换必须在调用方言之前完成，而不是在写 JSON 处
     */
    @Test
    public void dialectReadsTypedOptionFromConfig() throws Exception {
        responseBody = "{\"model\":\"x\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}";

        ChatConfig config = props(
                "apiUrl", url("/api/chat"),
                "model", "x",
                "defaultOptions.thinking", "false").bindTo(new ChatConfig());

        ChatModel.of(config).build().prompt("hi").call();

        ONode node = ONode.ofJson(requestBody);
        assertTrue(node.hasKey("think"), requestBody);
        assertFalse(node.get("think").getBoolean(), requestBody);
        assertFalse(node.hasKey("thinking"), requestBody);
    }

    /**
     * 代码里设置的嵌套字符串值也在发请求时还原；调用方传入的嵌套 Map 不被修改
     */
    @Test
    public void codeSetNestedOptionsAreTyped() throws Exception {
        responseBody = "{\"model\":\"bge-m3\",\"embeddings\":[[0.1,0.2]]}";

        EmbeddingConfig config = props(
                "apiUrl", url("/api/embed"),
                "model", "bge-m3").bindTo(new EmbeddingConfig());

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("num_ctx", "8192");

        EmbeddingModel.of(config).build().input("hello")
                .options(o -> o.optionSet("options", nested))
                .call();

        ONode node = ONode.ofJson(requestBody);
        assertTrue(node.get("options").get("num_ctx").isNumber(), requestBody);
        assertEquals(8192, node.get("options").get("num_ctx").getInt());
        assertEquals("8192", nested.get("num_ctx"));
    }

    @Test
    public void decisionOptionsAreTyped() throws Exception {
        //只看请求体，响应解析失败无关紧要
        responseBody = "{}";

        DecisionConfig config = props(
                "apiUrl", url("/api/chat"),
                "model", "x",
                "defaultOptions.temperature", "0",
                "defaultOptions.options.num_ctx", "4096").bindTo(new DecisionConfig());

        try {
            DecisionModel.of(config).build().input("hi")
                    .questionAdd("q", DecisionPredicateQuestion.of("ok?"))
                    .call();
        } catch (Exception ignored) {
        }

        ONode node = ONode.ofJson(requestBody);
        assertTrue(node.get("temperature").isNumber(), requestBody);
        assertEquals(0, node.get("temperature").getInt());
        assertEquals(4096, node.get("options").get("num_ctx").getInt());
        assertTrue(node.get("options").get("num_ctx").isNumber(), requestBody);

        //配置载体不变
        assertEquals("0", config.getDefaultOptions().get("temperature"));
    }
}
