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
package org.noear.solon.ai.ui.aisdk.part;

import org.noear.snack4.ONode;

/**
 * Data Part — 自定义数据（抽象）
 * <p>
 * 允许在流中传输任意结构化数据，type 值遵循 {@code data-*} 模式。
 * 子类需实现 {@link #getDataType()} 定义数据类型后缀，以及 {@link #getData()} 提供数据。
 * <p>
 * 也可通过 {@link #of(String, Object)} 工厂方法快速创建实例。
 * <p>
 * 格式：{@code {"type":"data-weather","data":{"location":"SF","temperature":100}}}
 *
 * <pre>{@code
 * // 方式一：工厂方法
 * DataPart part = DataPart.of("weather", Map.of("location", "SF", "temperature", 100));
 *
 * // 方式二：子类化
 * public class WeatherDataPart extends DataPart {
 *     private final WeatherInfo data;
 *     public WeatherDataPart(WeatherInfo data) { this.data = data; }
 *     public String getDataType() { return "weather"; }
 *     public Object getData() { return data; }
 * }
 * }</pre>
 *
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol#data-part">Data Part</a>
 * @since 3.9.5
 */
public abstract class DataPart extends AiSdkStreamPart {

    /**
     * 获取自定义数据类型后缀（如 "weather"、"progress" 等）
     * <p>
     * 最终 type 值为 {@code "data-" + getDataType()}
     */
    public abstract String getDataType();

    /**
     * 获取自定义数据对象
     */
    public abstract Object getData();

    @Override
    public final String getType() {
        return "data-" + getDataType();
    }

    @Override
    protected void writeFields(ONode node) {
        Object data = getData();
        if (data != null) {
            node.set("data", ONode.ofBean(data));
        }
    }

    /**
     * 快捷工厂方法：创建指定类型和数据的 DataPart
     *
     * @param dataType 数据类型后缀（如 "weather"），最终 type 为 "data-weather"
     * @param data     数据对象，将通过 {@code ONode.ofBean()} 序列化
     */
    public static DataPart of(String dataType, Object data) {
        return new DataPart() {
            @Override
            public String getDataType() {
                return dataType;
            }

            @Override
            public Object getData() {
                return data;
            }
        };
    }
}
