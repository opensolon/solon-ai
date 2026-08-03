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
package org.noear.solon.ai.talents.gateway.openapi.resolver;

import org.noear.snack4.ONode;
import org.noear.solon.lang.Preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Schema 引用（$ref）平铺器。
 *
 * <p>在 JSON（ONode）层面完成 $ref 展开，替代在 swagger 模型对象上的原地递归改写。相比模型层递归，
 * 它有三个好处：
 *
 * <ul>
 *     <li>环检测覆盖所有展开入口：任何一次 $ref 展开都会把 refName 压入引用路径，
 *         因此 A -&gt; B -&gt; B 这类嵌套自引用不会再无限递归；</li>
 *     <li>不污染定义表：始终构建新节点，definitions/components 中的原始定义保持不变，
 *         同一份文档里多个 operation 互不影响；</li>
 *     <li>保留全部 schema 关键字（type/format/required/enum/xml 等），不需要逐字段搬运。</li>
 * </ul>
 *
 * @author noear
 * @since 4.0.5
 */
@Preview("4.0.5")
public class SchemaRefFlattener {
    /**
     * 引用展开的最大深度（防御超深引用链导致的栈与体积膨胀）
     */
    private static final int MAX_REF_DEPTH = 32;
    /**
     * 引用展开的最大次数（防御 DAG 型定义的指数级膨胀）
     */
    private static final int MAX_EXPAND_COUNT = 5000;
    /**
     * 环引用占位描述
     */
    public static final String CIRCULAR_REFERENCE = "_Circular_Reference_";

    private final Map<String, ONode> definitions;

    /**
     * @param definitions 定义表（name -&gt; schema 节点），可为 null
     */
    public SchemaRefFlattener(Map<String, ONode> definitions) {
        this.definitions = definitions;
    }

    /**
     * 由定义表节点构建（如 swagger.definitions 或 openAPI.components.schemas 的 JSON 形态）
     *
     * @param definitionsNode 定义表节点，可为 null
     */
    public static SchemaRefFlattener of(ONode definitionsNode) {
        if (definitionsNode == null || definitionsNode.isObject() == false) {
            return new SchemaRefFlattener(null);
        }

        return new SchemaRefFlattener(definitionsNode.getObject());
    }

    /**
     * 平铺 schema 中的引用。
     *
     * <p>返回全新节点树，与入参（及内部定义表）不存在共享引用，可安全修改。
     *
     * @param schema 待平铺的 schema 节点
     */
    public ONode flatten(ONode schema) {
        if (schema == null) {
            return new ONode().asObject();
        }

        return resolve(schema, new ArrayList<>(), new int[1]);
    }

    private ONode resolve(ONode node, List<String> refPath, int[] expandCount) {
        if (node == null) {
            return null;
        }

        if (node.isArray()) {
            ONode out = new ONode().asArray();
            for (ONode item : node.getArray()) {
                out.add(resolve(item, refPath, expandCount));
            }
            return out;
        }

        if (node.isObject() == false) {
            // 标量节点不可能内含 $ref，做值拷贝，避免与定义表共享节点引用。
            // 注意：不能写 new ONode(node.getValueAs())，候选构造 ONode(Options) 更具体，
            // 会把 getValueAs() 的泛型 T 推断为 Options 并在运行时强转失败
            Object raw = node.getValueAs();
            return new ONode().setValue(raw);
        }

        ONode refNode = node.getOrNull("$ref");
        if (refNode != null && refNode.isString()) {
            return resolveRef(node, refNode.getString(), refPath, expandCount);
        }

        ONode out = new ONode().asObject();
        for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
            out.set(entry.getKey(), resolve(entry.getValue(), refPath, expandCount));
        }
        return out;
    }

    private ONode resolveRef(ONode node, String ref, List<String> refPath, int[] expandCount) {
        String refName = refNameOf(ref);
        ONode target = (definitions == null || refName == null) ? null : definitions.get(refName);

        if (target == null) {
            // 无法解析的引用：原样保留，便于排查，避免静默产出空结构
            return copyOf(node);
        }

        if (refPath.contains(refName)) {
            return circularNode();
        }

        if (refPath.size() >= MAX_REF_DEPTH || expandCount[0] >= MAX_EXPAND_COUNT) {
            return circularNode();
        }

        expandCount[0]++;
        refPath.add(refName);
        ONode resolved;
        try {
            resolved = resolve(target, refPath, expandCount);
        } finally {
            // 回溯：refPath 只表示“祖先链”，兄弟分支之间不互相污染
            refPath.remove(refPath.size() - 1);
        }

        // $ref 的兄弟关键字（如 description）覆盖被引用定义的同名关键字
        if (resolved.isObject()) {
            for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
                if ("$ref".equals(entry.getKey())) {
                    continue;
                }
                resolved.set(entry.getKey(), resolve(entry.getValue(), refPath, expandCount));
            }
        }

        return resolved;
    }

    private ONode circularNode() {
        return new ONode().asObject().set("description", CIRCULAR_REFERENCE);
    }

    private ONode copyOf(ONode node) {
        // 无法解析的引用：保留 $ref 及兄弟关键字，但整体重建，不与定义表共享节点
        ONode out = new ONode().asObject();
        for (Map.Entry<String, ONode> entry : node.getObject().entrySet()) {
            out.set(entry.getKey(), resolve(entry.getValue(), new ArrayList<>(), new int[1]));
        }
        return out;
    }

    /**
     * 取引用名（兼容 #/definitions/X 与 #/components/schemas/X）
     */
    private String refNameOf(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }

        int idx = ref.lastIndexOf('/');
        return (idx < 0) ? ref : ref.substring(idx + 1);
    }
}
