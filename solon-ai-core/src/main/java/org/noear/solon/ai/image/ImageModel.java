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
package org.noear.solon.ai.image;

import org.noear.solon.ai.AiModel;
import org.noear.solon.ai.image.dialect.ImageDialect;
import org.noear.solon.ai.image.dialect.ImageDialectManager;
import org.noear.solon.core.Props;
import org.noear.solon.lang.Preview;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.Properties;

/**
 * 图像模型
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public class ImageModel implements AiModel {
    private final ImageConfig config;
    private final ImageDialect dialect;

    public ImageModel(Properties properties) {
        //支持直接注入
        this(Props.from(properties).bindTo(new ImageConfig()));
    }

    public ImageModel(ImageConfig config) {
        this.dialect = ImageDialectManager.select(config);
        this.config = config;
    }


    /**
     * 输入
     */
    public ImageRequestDesc prompt(String prompt) {
        return new ImageRequestDesc(config, dialect, prompt);
    }


    @Override
    public String toString() {
        return "ImageModel{" +
                "config=" + config +
                ", dialect=" + dialect.getClass().getName() +
                '}';
    }

    /**
     * 构建
     */
    public static Builder of(ImageConfig config) {
        return new Builder(config);
    }

    /**
     * 构建
     */
    public static Builder of(String apiUrl) {
        return new Builder(apiUrl);
    }

    /// /////////////

    /**
     * 嵌入模型构建器实现
     *
     * @author noear
     * @since 3.1
     */
    public static class Builder {
        private final ImageConfig config;

        /**
         * @param apiUrl 接口地址
         */
        public Builder(String apiUrl) {
            this.config = new ImageConfig();
            this.config.setApiUrl(apiUrl);
        }

        /**
         * @param config 配置
         */
        public Builder(ImageConfig config) {
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
         * 头信息设置
         */
        public Builder headerSet(String key, String value) {
            config.setHeader(key, value);
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
            config.setProxy(proxy);

            return this;
        }

        /**
         * 网络代理
         */
        public Builder proxy(String host, int port) {
            return proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
        }

        public ImageModel build() {
            return new ImageModel(config);
        }
    }
}