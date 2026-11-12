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
package org.noear.solon.ai.harness.extension.steer;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.command.AbsCommand;
import org.noear.solon.ai.harness.command.CommandInput;
import org.noear.solon.ai.harness.command.CommandOutput;
import org.noear.solon.ai.harness.extension.steer.i18n.SteerI18n;
import org.noear.solon.ai.harness.extension.steer.i18n.SteerI18nKeys;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * /steer 命令 - 向正在执行的任务插入实时补充（插话）。
 *
 * <p>CLI 前端用 Enter 提交插话、Web 前端调 /web/chat/steer，但 IM 渠道（微信/飞书/钉钉）
 * 缺少插话途径。此命令为 IM 场景提供统一的插话能力：把补充消息注入<b>当前</b>任务的工作记忆，
 * 由插话拦截器在下一个采样边界消费，不打断进行中的模型流与工具调用。</p>
 *
 * <p>{@link #runnableWhenBusy()} 返回 true，使其可穿透统一输入入口的忙碌检查、在任务执行中被受理。
 * 本命令<b>只做投递，不做降级</b>：插话邮箱满、任务未运行或轮次已切换时直接告知失败原因，
 * 由用户自行决定是否改用 /queue 排队或直接发送——与 /web/chat/steer 的 409 纯语义对齐
 * （Web 端同样是失败后由前端决策转排队/普通发送，后端不代为降级）。</p>
 *
 * <p><b>经门面投递</b>：与 Web / IM 同一条路径（{@link SteerExtension#steer(AgentSession, String, InputOrigin)}），
 * 因此开关（DISABLED）与忙判定复查口径一致——任务异常退出后不会再把插话投进一个不会再运行的邮箱。</p>
 *
 * @author noear
 * @since 4.2.0
 */
class SteerCommand extends AbsCommand {
    /** 所属门面（由 {@link SteerExtension} 创建时注入）；独立 new 时为 null，执行期从引擎解析 */
    private final SteerExtension owner;

    /** 独立注册用：执行期从引擎取 {@link SteerExtension}，取不到时用缺省约束的无引擎门面 */
    public SteerCommand() {
        this(null);
    }

    SteerCommand(SteerExtension owner) {
        this.owner = owner;
    }

    @Override
    public String name() {
        return "steer";
    }


    @Override
    public String description(@Nullable Locale locale) {
        return SteerI18n.get(locale, SteerI18nKeys.COMMAND_STEER_DESCRIPTION);
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{
                SteerI18n.get(locale, SteerI18nKeys.COMMAND_STEER_EXAMPLE_1),
                SteerI18n.get(locale, SteerI18nKeys.COMMAND_STEER_EXAMPLE_2)
        };
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;
    }

    /**
     * 解析投递门面：优先所属扩展，其次引擎上装配的扩展，都没有时回落无引擎门面。
     *
     * <p>提示文案读同一个门面的 {@link SteerExtension#getMaxBoxSize()} 等，与投递实际使用的约束同源。</p>
     */
    private SteerExtension facade(HarnessEngine engine) {
        if (owner != null) {
            return owner;
        }
        SteerExtension attached = engine == null ? null : engine.getExtension(SteerExtension.class).orElse(null);
        return attached != null ? attached : Detached.INSTANCE;
    }

    /** 无引擎门面（缺省约束、忙判定放行），惰性创建 */
    private static final class Detached {
        static final SteerExtension INSTANCE = new SteerExtension();
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
        Locale locale = input.getLocale();
        String text = input.getArgsJoined();
        if (Assert.isEmpty(text)) {
            output.println(SteerI18n.get(locale, SteerI18nKeys.COMMAND_STEER_USAGE));
            return;
        }

        AgentSession session = input.getSession();
        InputOrigin resolved = input.getOrigin();
        if (resolved.getChannel() == null || resolved.getChannel().isEmpty()) {
            resolved = InputOrigin.of("command", resolved.getSenderId(),
                    resolved.getReplyTarget(), resolved.getMessageId());
        }

        SteerExtension steer = facade(engine);
        HarnessResult<SteerReceipt> result = steer.steer(session, text, resolved);

        if (result.isOk()) {
            output.println(SteerI18n.fmt(locale, SteerI18nKeys.COMMAND_STEER_APPLIED, "pending", result.getValue().getPending()));
        } else if (result.getCode() == HarnessResult.Code.DISABLED) {
            output.println(SteerI18n.get(locale, SteerI18nKeys.COMMAND_STEER_DISABLED));
        } else if (result.hasReason(SteerExtension.REASON_BOX_FULL)) {
            output.println(SteerI18n.fmt(locale, SteerI18nKeys.COMMAND_STEER_BOX_FULL, "max", steer.getMaxBoxSize()));
        } else if (result.hasReason(SteerExtension.REASON_NOT_RUNNING)
                || result.hasReason(SteerExtension.REASON_RUN_CHANGED)) {
            output.println(SteerI18n.get(locale, SteerI18nKeys.COMMAND_STEER_NOT_RUNNING));
        } else if (result.hasReason(SteerExtension.REASON_TEXT_TOO_LONG)) {
            output.println(SteerI18n.fmt(locale, SteerI18nKeys.COMMAND_STEER_TOO_LONG, "max", steer.getMaxTextLength()));
        } else {
            output.println(SteerI18n.fmt(locale, SteerI18nKeys.COMMAND_STEER_FAILED, "reason", result.getReasonOrCode()));
        }
    }
}
