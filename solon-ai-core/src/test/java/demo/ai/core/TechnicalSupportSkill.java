package demo.ai.core;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillMetadata;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.annotation.Param;

import java.util.Collection;

/**
 * 技术支持技能：展示多级决策与 RAG 结合
 */
public class TechnicalSupportSkill implements Skill {
    private final SkillMetadata metadata = new SkillMetadata("tech_support", "多级技术支持与知识库检索")
            .category("support")
            .tags("rag", "helpdesk")
            .sensitive(false);
    private final ToolProvider toolProvider = new MethodToolProvider(this);

    @Override
    public String name() { return metadata.getName(); }

    @Override
    public String description() { return metadata.getDescription(); }

    @Override
    public Collection<FunctionTool> getTools() {
        return toolProvider.getTools();
    }

    @Override
    public String getInstruction(ChatPrompt prompt) {
        String userMessage = prompt.getUserMessageContent();

        // 如果用户直接贴了代码或错误栈，调整 SOP 优先级
        if (userMessage.contains("StackOverflow") || userMessage.contains("Exception")) {
            return "检测到具体异常，请跳过基础搜索，优先调用 'search_online_docs' 查找最新补丁。";
        }

        return "处理技术咨询时必须遵循以下 SOP：\n" +
                "1. 优先调用 'search_knowledge_base' 获取标准答案。\n" +
                "2. 如果知识库无法解决或信息过时，调用 'search_online_docs' 获取最新文档。\n" +
                "3. 只有当前两步都无法给出确切方案，且用户问题属于紧急故障时，才允许调用 'create_human_ticket'。";
    }

    @ToolMapping(description = "检索内部私有知识库 (已核实的标准操作手册)")
    public String searchKnowledgeBase(@Param("query") String query) {
        // 模拟 RAG 检索
        if (query.contains("安装")) {
            return "知识库命中：请运行 'npm install solon-ai' 并配置 API Key。";
        }
        return "知识库未命中相关词条。";
    }

    @ToolMapping(description = "检索实时在线开发文档 (最新 API 变更和社区讨论)")
    public String searchOnlineDocs(@Param("url_path") String path) {
        // 模拟爬虫或文档 API
        return "在线文档显示：3.8.4 版本后，Skill 接口新增了 injectInstruction 方法。";
    }

    @ToolMapping(
            description = "创建人工支持工单",
            // 增加确认话术和操作等级
            meta = "{'danger':3, 'confirm_msg':'确定要转接到人工座席吗？可能会产生额外费用。'}"
    )
    public String createHumanTicket(@Param("issue") String issue, @Param("urgency") String urgency) {
        // 标记为敏感/破坏性操作，会触发你在 ReActSystemPrompt 里的安全约束
        return "工单已提交成功，工单号：TICK-" + System.currentTimeMillis();
    }
}