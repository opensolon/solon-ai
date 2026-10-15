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
package org.noear.solon.ai.decision;

import org.noear.solon.ai.AiModel;
import org.noear.solon.ai.decision.dialect.DecisionDialect;
import org.noear.solon.ai.decision.dialect.DecisionDialectManager;
import org.noear.solon.core.Props;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * 决策模型
 *
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public class DecisionModel implements AiModel {
    private final DecisionConfig config;
    private final DecisionDialect dialect;

    /**
     * 支持 Properties 配置直接注入
     */
    public DecisionModel(Properties properties) {
        this(Props.from(properties).bindTo(new DecisionConfig()));
    }

    /**
     * 由配置创建模型
     */
    public DecisionModel(DecisionConfig config) {
        Assert.notNull(config, "The config is required");
        Assert.notNull(config.getApiUrl(), "The config.apiUrl is required");
        Assert.notNull(config.getModel(), "The config.model is required");
        this.config = config;
        this.dialect = DecisionDialectManager.select(config);
        Assert.notNull(dialect, "The dialect(provider) no matched, check config or dependencies");
    }

    /**
     * 获取方言
     */
    public DecisionDialect getDialect() {
        return dialect;
    }

    /**
     * 管理名称或模型名
     */
    public String getNameOrModel() {
        return config.getNameOrModel();
    }

    /**
     * 模型名
     */
    public String getModel() {
        return config.getModel();
    }

    /**
     * 接口规范
     */
    public String getStandard() {
        return config.getStandard();
    }

    /**
     * 接口规范优先，未设置时使用提供者
     */
    public String getStandardOrProvider() {
        return config.getStandardOrProvider();
    }

    /**
     * 服务提供者
     */
    public String getProvider() {
        return config.getProvider();
    }

    /**
     * 输入共享上下文；需要结构化内容时由调用方先序列化为 JSON 文本
     */
    public DecisionRequestDesc input(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new DecisionException("The input is required");
        }
        return new DecisionRequestDesc(config, dialect, input);
    }

    @Override
    public String toString() {
        return "DecisionModel{" +
                "config=" + config +
                ", dialect=" + dialect.getClass().getName() +
                '}';
    }

    /**
     * 由配置开始构建
     */
    public static Builder of(DecisionConfig config) {
        return new Builder(config);
    }

    /**
     * 由完整接口地址开始构建
     */
    public static Builder of(String apiUrl) {
        return new Builder(apiUrl);
    }

    /**
     * 决策模型构建器
     *
     * @author devlau
     * @since 4.1.1
     */
    public static class Builder {
        private final DecisionConfig config;

        /**
         * @param apiUrl 完整接口地址
         */
        public Builder(String apiUrl) {
            config = new DecisionConfig();
            config.setApiUrl(apiUrl);
        }

        /**
         * @param config 模型配置
         */
        public Builder(DecisionConfig config) {
            Assert.notNull(config, "The config is required");
            this.config = config;
        }

        /**
         * 接口密钥
         */
        public Builder apiKey(String apiKey) {
            config.setApiKey(apiKey);
            return this;
        }

        /**
         * 接口规范
         */
        public Builder standard(String standard) {
            config.setStandard(standard);
            return this;
        }

        /**
         * 服务提供者
         */
        public Builder provider(String provider) {
            config.setProvider(provider);
            return this;
        }

        /**
         * 使用模型
         */
        public Builder model(String model) {
            config.setModel(model);
            return this;
        }

        /**
         * User-Agent
         */
        public Builder userAgent(String userAgent) {
            config.setUserAgent(userAgent);
            return this;
        }

        /**
         * 网络超时
         */
        public Builder timeout(Duration timeout) {
            config.setTimeout(timeout);
            return this;
        }

        /**
         * 网络代理
         */
        public Builder proxy(Proxy proxy) {
            config.setProxyInstance(proxy);
            return this;
        }

        /**
         * 请求头
         */
        public Builder headerSet(String key, String value) {
            config.setHeader(key, value);
            return this;
        }

        /**
         * HTTP 代理
         */
        public Builder proxy(String host, int port) {
            return proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
        }

        /**
         * 模型默认选项
         */
        public Builder modelOptions(Consumer<DecisionOptions> consumer) {
            consumer.accept(config.getModelOptions());
            return this;
        }

        /**
         * 构建模型
         */
        public DecisionModel build() {
            return new DecisionModel(config);
        }
    }
}
