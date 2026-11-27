/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.AgentSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子代理事件转给父流时不能带着中断标志。
 *
 * <p>真机现象：轮次结束取消后台任务，子任务线程停在 bash_wait 被打断、留下中断标志，随后转发事件时
 * 替父代理把排队的事件写到 WebSocket（Flux.create 由正在推的线程一起推下去），NIO 写抛
 * ClosedByInterruptException，连接被关、页面漏收本轮余下的事件。这里用文件通道模拟宿主的网络写。</p>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentForwardTest {

    @Test
    void forwardWithInterruptFlagKeepsDownstreamChannelOpen(@TempDir Path dir) throws Exception {
        try (FileChannel channel = FileChannel.open(dir.resolve("ws.out"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            AtomicReference<FluxSink<AgentEvent>> sinkRef = new AtomicReference<>();
            AtomicReference<Throwable> writeError = new AtomicReference<>();

            Flux.<AgentEvent>create(sinkRef::set).subscribe(e -> {
                try {
                    channel.write(ByteBuffer.wrap(e.getText().getBytes(StandardCharsets.UTF_8)));
                } catch (Throwable ex) {
                    writeError.set(ex);
                }
            });

            Thread.currentThread().interrupt();
            try {
                TaskTalent.forward(sinkRef.get(), event("hello"));
            } finally {
                // 中断标志要还回去（停止逻辑还靠它），同时清掉，免得影响后续测试
                assertTrue(Thread.interrupted(), "中断标志应保留");
            }

            assertNull(writeError.get(), "下游写入不应因中断失败");
            assertTrue(channel.isOpen(), "下游通道不应被关闭");
            assertEquals(5L, channel.size());
        }
    }

    private static AgentEvent event(String text) {
        return new AgentEvent() {
            @Override
            public String getRunId() {
                return "run";
            }

            @Override
            public String getAgentName() {
                return "explore";
            }

            @Override
            public AgentSession getSession() {
                return null;
            }

            @Override
            public Map<String, Object> getMeta() {
                return Collections.emptyMap();
            }

            @Override
            public boolean hasMeta(String name) {
                return false;
            }

            @Override
            public String getText() {
                return text;
            }
        };
    }
}
