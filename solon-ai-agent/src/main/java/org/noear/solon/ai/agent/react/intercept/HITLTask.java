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
package org.noear.solon.ai.agent.react.intercept;

import org.noear.solon.lang.Preview;

import java.io.Serializable;
import java.util.Map;

/**
 * HITL 挂起任务实体
 * <p>承载被拦截瞬间的工具调用上下文快照，供前端或 UI 层渲染审批界面</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class HITLTask implements Serializable {
    /** 拟调用的工具名称 */
    private String toolName;
    /** 拟调用的原始参数快照 */
    private Map<String, Object> args;
    /** 触发拦截的系统理由/备注 */
    private String comment;

    public HITLTask() {
        //用于反序列化
    }

    public HITLTask(String toolName, Map<String, Object> args, String comment) {
        this.toolName = toolName;
        this.args = args;
        this.comment = comment;

    }

    /**
     * 获取拟调用的工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取拟调用的参数快照
     */
    public Map<String, Object> getArgs() {
        return args;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public String toString() {
        return "HITLTask{" +
                "toolName='" + toolName + '\'' +
                ", args=" + args +
                ", comment='" + comment + '\'' +
                '}';
    }
}