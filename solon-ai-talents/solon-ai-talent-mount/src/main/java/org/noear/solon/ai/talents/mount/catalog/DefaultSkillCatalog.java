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
package org.noear.solon.ai.talents.mount.catalog;

import org.noear.solon.ai.talents.mount.*;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 
 * 基于 MountSource 的默认技能目录。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public class DefaultSkillCatalog implements SkillCatalog {
    private final MountManager mountManager;
    private volatile Map<String, SkillRecord> skills = Collections.emptyMap();
    private volatile Map<String, SkillRecord> shortNames = Collections.emptyMap();

    /**
     * 创建目录并立即扫描所有技能来源。
     *
     * @param mountManager 挂载管理器
     */
    public DefaultSkillCatalog(MountManager mountManager) {
        this.mountManager = mountManager;
        refresh();
    }

    /**
     * 全量扫描已启用的技能挂载，并重建标识及唯一短名索引。
     */
    @Override
    public synchronized void refresh() {
        Map<String, SkillRecord> result = new LinkedHashMap<>();
        for (Mount mount : mountManager.getSourceMounts()) {
            if (mount.isEnabled() && mount.getType() == MountType.SKILLS) {
                scanMount(mount, result);
            }
        }
        Map<String, SkillRecord> names = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (SkillRecord record : result.values()) {
            String name = record.descriptor.getName();
            if (names.putIfAbsent(name, record) != null) ambiguous.add(name);
        }
        for (String name : ambiguous) names.remove(name);
        shortNames = Collections.unmodifiableMap(names);
        skills = Collections.unmodifiableMap(result);
    }

    /**
     * 全量刷新技能目录，不按挂载别名局部刷新。
     *
     * @param mountAlias 挂载别名；此实现不使用该参数
     */
    @Override
    public void refreshByMount(String mountAlias) {
        refresh();
    }

    /**
     * 统计当前允许访问的技能数量。
     *
     * @return 允许访问的技能数量
     */
    @Override
    public int getSkillCount() {
        return (int) getDescriptors().stream().filter(this::isAllowed).count();
    }

    /**
     * 获取全部已索引的技能描述，不进行权限过滤。
     *
     * @return 全部技能描述
     */
    @Override
    public Collection<SkillDescriptor> getDescriptors() {
        return skills.values().stream().map(record -> record.descriptor).collect(Collectors.toList());
    }

    /**
     * 搜索允许访问且名称、描述或标识匹配任一关键词的技能。
     *
     * @param query 空白分隔的搜索关键词
     * @return 最多 15 条匹配结果；查询为空时返回空集合
     */
    @Override
    public Collection<SkillDescriptor> searchDescriptors(String query) {
        if (Assert.isEmpty(query)) return Collections.emptyList();
        String[] keys = query.toLowerCase().trim().split("\\s+");
        return skills.values().stream().map(record -> record.descriptor)
                .filter(this::isAllowed)
                .filter(s -> Arrays.stream(keys).anyMatch(k ->
                        s.getName().toLowerCase().contains(k)
                                || s.getDescription().toLowerCase().contains(k)
                                || s.getId().toLowerCase().contains(k)))
                .limit(15).collect(Collectors.toList());
    }

    /**
     * 按标识或唯一短名查找技能描述，不进行权限过滤。
     *
     * @param name 技能标识或短名
     * @return 技能描述；未找到时返回 null
     */
    @Override
    public SkillDescriptor getDescriptor(String name) {
        SkillRecord record = find(name);
        return record == null ? null : record.descriptor;
    }

    /**
     * 读取技能原文并生成包含文件清单的展示内容。
     *
     * @param name 技能标识或短名
     * @return 技能内容；未找到、不允许访问或读取失败时返回 null
     */
    @Override
    public SkillContent readContent(String name) {
        SkillRecord record = find(name);
        if (record == null || !isAllowed(record.descriptor)) return null;
        try (InputStream input = record.source.openRead(record.markerPath)) {
            String text = new String(readAll(input), StandardCharsets.UTF_8);
            return new SkillContent(record.descriptor, text, renderSkillXml(record, text));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 检查技能是否已索引且未被挂载管理器禁止访问。
     *
     * @param descriptor 待检查的技能描述
     * @return 允许访问时为 true，否则为 false
     */
    @Override
    public boolean isAllowed(SkillDescriptor descriptor) {
        return descriptor != null && skills.containsKey(descriptor.getId())
                && !mountManager.isSkillDisallowed(descriptor.getId());
    }

    /**
     * 按原始标识、补全挂载前缀的标识或唯一短名查找记录。
     *
     * @param name 技能标识或短名
     * @return 技能记录；未找到时返回 null
     */
    private SkillRecord find(String name) {
        if (name == null) return null;
        SkillRecord record = skills.get(name);
        if (record == null && !name.startsWith("@")) record = skills.get("@" + name);
        if (record == null) record = shortNames.get(name);
        return record;
    }

    /**
     * 扫描挂载中的技能标记文件并加入索引；来源不可用时跳过。
     *
     * @param mount 待扫描的挂载
     * @param result 接收技能记录的索引
     */
    private void scanMount(Mount mount, Map<String, SkillRecord> result) {
        MountSource source = mount.getSource();
        try {
            List<MountEntry> markers = new ArrayList<>(source.find("", FindOptions.builder()
                    .glob("**/SKILL.md").maxDepth(3).maxEntries(10000).filesOnly(true).build()));
            markers.addAll(source.find("", FindOptions.builder()
                    .glob("**/skill.md").maxDepth(3).maxEntries(10000).filesOnly(true).build()));
            for (MountEntry marker : markers) {
                String markerPath = marker.getPath();
                int slash = markerPath.lastIndexOf('/');
                String sourcePath = slash < 0 ? "" : markerPath.substring(0, slash);
                String name = sourcePath.isEmpty() ? mount.getAlias().substring(1) : sourcePath;
                String id = mount.getAlias() + (sourcePath.isEmpty() ? "" : "/" + sourcePath);
                Markdown markdown = parseMarkdown(source, markerPath);
                SkillDescriptor descriptor = new SkillDescriptor(id, name, markdown.getDescription(), markdown.getVersion());
                result.putIfAbsent(id, new SkillRecord(descriptor, source, sourcePath, markerPath));
            }
        } catch (IOException e) {
            // 单个来源不可用不应阻断其他技能来源。
        }
    }

    /**
     * 解析技能标记文件的 Markdown 元数据，失败时返回空元数据。
     *
     * @param source 技能来源
     * @param path 标记文件路径
     * @return 解析得到的 Markdown 元数据
     */
    private Markdown parseMarkdown(MountSource source, String path) {
        try (InputStream input = source.openRead(path)) {
            List<String> lines = Arrays.asList(new String(readAll(input), StandardCharsets.UTF_8).split("\\R", -1));
            return MarkdownUtil.resolve(lines, true);
        } catch (Throwable e) {
            return new Markdown();
        }
    }

    /**
     * 将技能正文、访问提示与文件清单包装为 XML 展示文本。
     *
     * @param record 技能记录
     * @param content 技能正文
     * @return XML 展示文本
     * @throws IOException 枚举技能文件失败时抛出
     */
    private String renderSkillXml(SkillRecord record, String content) throws IOException {
        StringBuilder sb = new StringBuilder("\n<skill_content name=\"")
                .append(record.descriptor.getName()).append("\">\n");
        if (record.source.capabilities().isShellAccessible()) {
            sb.append("[SYSTEM NOTE: Access granted. Use the <alias> paths in 'bash' tool for execution.]\n");
        } else {
            sb.append("[SYSTEM NOTE: This mount is readable through file tools but is not directly accessible to bash.]\n");
        }
        sb.append("[If this task takes many steps, remember to re-read this skill if you feel uncertain about details.]\n\n");
        sb.append(content.trim()).append("\n\n<skill_files>\n");
        sb.append(sampleFiles(record)).append("</skill_files>\n</skill_content>\n");
        return sb.toString();
    }

    /**
     * 枚举技能文件并生成文件清单，跳过标记文件及隐藏文件。
     *
     * @param record 技能记录
     * @return XML 文件条目文本
     * @throws IOException 枚举技能文件失败时抛出
     */
    private String sampleFiles(SkillRecord record) throws IOException {
        Set<String> ignored = new HashSet<>(Arrays.asList(
                ".DS_Store", "__pycache__", ".git", ".idea", ".vscode", "node_modules", "venv"));
        StringBuilder result = new StringBuilder();
        for (MountEntry entry : record.source.find(record.sourcePath,
                FindOptions.builder().maxDepth(3).maxEntries(500).filesOnly(true).build())) {
            String relative = entry.getPath();
            if (!record.sourcePath.isEmpty() && relative.startsWith(record.sourcePath + "/")) {
                relative = relative.substring(record.sourcePath.length() + 1);
            }
            String name = entry.getName();
            if (name.equalsIgnoreCase("SKILL.md") || ignored.contains(name) || name.startsWith(".")) continue;
            result.append("  <file>\n    <rel>").append(relative)
                    .append("</rel>\n    <logicalPath>")
                    .append(record.descriptor.getId()).append('/').append(relative)
                    .append("</logicalPath>\n  </file>\n");
        }
        return result.toString();
    }

    /**
     * 将输入流中的全部数据读入字节数组。
     *
     * @param input 输入流
     * @return 输入流的全部字节
     * @throws IOException 读取失败时抛出
     */
    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static final class SkillRecord {
        final SkillDescriptor descriptor;
        final MountSource source;
        final String sourcePath;
        final String markerPath;

        /**
         * 保存技能描述、来源及其路径。
         *
         * @param descriptor 技能描述
         * @param source 技能来源
         * @param sourcePath 技能所在目录路径
         * @param markerPath 技能标记文件路径
         */
        SkillRecord(SkillDescriptor descriptor, MountSource source, String sourcePath, String markerPath) {
            this.descriptor = descriptor;
            this.source = source;
            this.sourcePath = sourcePath;
            this.markerPath = markerPath;
        }
    }
}
