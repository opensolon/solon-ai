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
package org.noear.solon.ai.skills.social;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;

/**
 * 企业微信专家技能
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class WeComSkill extends AbsWebhookSkill {
    public WeComSkill(String webhookUrl) { super(webhookUrl); }

    @Override public String name() { return "wecom_sender"; }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        return content.contains("企微") || content.contains("企业微信") || content.contains("wecom");
    }

    @ToolMapping(name = "send_wecom", description = "发送企业微信消息。内容支持 Markdown 格式。")
    public String send(@Param("content") String content) {
        try {
            ONode data = new ONode();

            // 简单逻辑：如果内容包含 Markdown 常见标记，则使用 markdown 模式
            if (content.contains("#") || content.contains("**") || content.contains(">")) {
                data.set("msgtype", "markdown");
                data.getOrNew("markdown").set("content", content);
            } else {
                data.set("msgtype", "text");
                data.getOrNew("text").set("content", content);
            }

            String res = postJson(data.toJson());
            return res.contains("\"errcode\":0") ? "发送成功" : "发送失败: " + res;
        } catch (Exception e) {
            return "发送异常: " + e.getMessage();
        }
    }
}