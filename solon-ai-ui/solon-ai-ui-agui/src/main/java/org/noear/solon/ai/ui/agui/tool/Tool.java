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
package org.noear.solon.ai.ui.agui.tool;

import java.util.List;
import java.util.Map;

/**
 * AG-UI 协议工具定义，描述可供 Agent 调用的工具及其参数结构
 *
 * @author shaoerkuai
 * @since 3.10.5
 * @see <a href="https://docs.ag-ui.com/concepts/tools">AG-UI Tools</a>
 */
public class Tool {
    /** 工具名称 */
    private final String name;
    /** 工具描述 */
    private final String description;
    /** 工具参数定义 */
    private final ToolParameters parameters;

    public Tool(String name, String description, ToolParameters parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ToolParameters getParameters() {
        return parameters;
    }

    /**
     * 工具参数定义，遵循 JSON Schema 结构
     */
    public static class ToolParameters {
        /** 参数类型（通常为 "object"） */
        private final String type;
        /** 参数属性映射 */
        private final Map<String, ToolProperty> properties;
        /** 必填参数名称列表 */
        private final List<String> required;

        public ToolParameters(String type, Map<String, ToolProperty> properties, List<String> required) {
            this.type = type;
            this.properties = properties;
            this.required = required;
        }

        public String getType() {
            return type;
        }

        public Map<String, ToolProperty> getProperties() {
            return properties;
        }

        public List<String> getRequired() {
            return required;
        }
    }

    /**
     * 工具属性定义，描述单个参数的类型和说明
     */
    public static class ToolProperty {
        /** 属性数据类型（如 "string"、"number"、"boolean"） */
        private final String type;
        /** 属性描述 */
        private final String description;

        public ToolProperty(String type, String description) {
            this.type = type;
            this.description = description;
        }

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }
    }
}