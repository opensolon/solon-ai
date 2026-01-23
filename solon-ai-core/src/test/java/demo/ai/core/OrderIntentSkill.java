package demo.ai.core;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

import java.util.Arrays;
import java.util.List;

/**
 * 意图感知的订单技能
 */
public class OrderIntentSkill extends AbsSkill implements Skill {
    // 定义该技能关心的意图关键词
    private static final List<String> INTENT_KEYWORDS = Arrays.asList("订单", "买过", "物流", "发货", "退款");

    @Override
    public String name() {
        return "order_manager";
    }

    @Override
    public String description() {
        return "处理所有与订单、物流相关的业务请求";
    }

    /**
     * 核心：准入检查
     * 只有当用户提问包含相关关键词时，此技能才会被激活
     */
    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent();
        if (content == null) return false;

        // 简单的关键词匹配（生产环境可以换成正则或微型分类模型）
        return INTENT_KEYWORDS.stream().anyMatch(content::contains);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "1. 查询订单前，请确认用户是否提供了订单号或手机号。\n" +
                "2. 如果物流状态显示为'已签收'，主动询问用户对商品的满意度。";
    }

    @ToolMapping(description = "根据订单号查询详细的物流信息")
    public String getOrderLogistics(@Param("orderId") String orderId) {
        return "订单 " + orderId + " 正在上海分拣中心处理中...";
    }
}