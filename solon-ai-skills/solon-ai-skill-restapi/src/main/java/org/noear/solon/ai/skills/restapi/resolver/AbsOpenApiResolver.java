/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.skills.restapi.resolver;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.skills.restapi.ApiResolver;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * OpenAPI 解析器抽象基类
 * <p>提供了对 $ref 引用的递归解析能力，并支持自动提取 API 基础信息</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public abstract class AbsOpenApiResolver implements ApiResolver {
    private static final Logger LOG = LoggerFactory.getLogger(AbsOpenApiResolver.class);
    /**
     * 解析引用节点
     *
     * @param node 包含 $ref 的节点或原始 Schema 节点
     */
    protected String resolveRef(ONode root, ONode node) {
        if (node == null || node.isNull()) return "{}";
        return resolveRefNode(root, node, new HashSet<>()).toJson();
    }

    /**
     * 递归解析 $ref 节点，并处理循环引用
     *
     * @param visited 记录已访问的引用路径，防止死循环
     */
    protected ONode resolveRefNode(ONode root, ONode node, Set<String> visited) {
        if (node == null || node.isNull()) return new ONode();

        // 1. 解析 $ref (规范支持：JSON Pointer)
        if (node.hasKey("$ref")) {
            String ref = node.get("$ref").getString();
            if (visited.contains(ref)) return ONode.ofBean("_Circular_Reference_");
            visited.add(ref);

            String[] parts = ref.split("/");
            ONode refNode = null;
            for (String p : parts) {
                if ("#".equals(p)) refNode = root;
                else if (refNode != null) refNode = refNode.get(p);
            }
            return resolveRefNode(root, refNode, visited);
        }

        // 2. 数据清洗：仅保留符合 JSON Schema 子集规范的字段
        if (node.isObject()) {
            ONode cleanNode = new ONode().asObject();
            node.getObjectUnsafe().forEach((k, v) -> {
                // 重点：k.contains("Of") 用于支持 oneOf, anyOf, allOf 规范
                if (isValidSchemaKey(k)) {
                    if ("properties".equals(k)) {
                        ONode props = cleanNode.getOrNew("properties").asObject();
                        v.getObjectUnsafe().forEach((pk, pv) ->
                                props.set(pk, resolveRefNode(root, pv, new HashSet<>(visited))));
                    } else if ("items".equals(k)) {
                        cleanNode.set("items", resolveRefNode(root, v, new HashSet<>(visited)));
                    } else {
                        cleanNode.set(k, v);
                    }
                }
            });
            return cleanNode;
        } else if (node.isArray()) {
            ONode cleanArray = new ONode().asArray();
            node.getArrayUnsafe().forEach(n ->
                    cleanArray.add(resolveRefNode(root, n, new HashSet<>(visited))));
            return cleanArray;
        }
        return node;
    }

    private boolean isValidSchemaKey(String k) {
        return "type".equals(k) || "properties".equals(k) || "items".equals(k) ||
                "required".equals(k) || "description".equals(k) || "enum".equals(k) ||
                "name".equals(k) || "in".equals(k) || "format".equals(k) ||
                "default".equals(k) || "example".equals(k) || // 增加 example 辅助 AI 理解
                "maximum".equals(k) || "minimum".equals(k) || // 增加数值约束
                "maxLength".equals(k) || "minLength".equals(k) || // 增加长度约束
                "pattern".equals(k) || k.contains("Of");
    }

    /**
     * 过滤无效或不常用的 HTTP 方法
     */
    protected boolean isValidMethod(String method) {
        return !method.startsWith("x-") && !"options".equalsIgnoreCase(method);
    }

    /**
     * 生成工具名称：优先使用 operationId，否则根据 method 和 path 生成
     */
    protected String generateName(ONode detail, String method, String path) {
        String opId = detail.get("operationId").getString();
        if (Utils.isNotEmpty(opId)) return opId;
        return (method + "_" + path.replaceAll("[^a-zA-Z0-9]", "_")).replaceAll("_+", "_").toLowerCase();
    }

    /**
     * 提取 API 描述信息
     */
    protected String extractDescription(ONode detail) {
        return Utils.valueOr(
                detail.get("summary").getString(),
                detail.get("description").getString(),
                "");
    }
}