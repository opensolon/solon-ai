package features.ai.chat;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;
import org.noear.solon.test.SolonTest;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author noear 2025/2/10 created
 */
@Configuration
@SolonTest
public class App {
    @Bean
    public ChatModel build(@Inject("${solon.ai.chat.openai}") ChatModel chatModel) {
        return chatModel;
    }

    @Inject
    ChatModel chatModel;

    @Test
    public void case1() throws IOException {
        //一次性返回
        ChatResponse resp = chatModel.prompt("hello").call();
        System.out.println("=========----------===========");
        //打印消息
        System.out.println(resp.getMessage());
    }

    @Test
    public void case2() throws IOException, InterruptedException {
        System.out.println("===========================================");
        System.out.println("开始流式测试（带思考模式）...");
        System.out.println("===========================================\n");

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder fullResponse = new StringBuilder();
        StringBuilder thinkingResponse = new StringBuilder();
        StringBuilder resultResponse = new StringBuilder();
        int[] chunkCount = {0};
        int[] thinkingChunkCount = {0};
        int[] resultChunkCount = {0};
        boolean[] finishedReported = {false};

        //手写 Subscriber + 背压（验证事件流对原生 reactive-streams 的兼容性）
        Publisher<ChatEvent> stream = chatModel.prompt("请问1+2+3+4+5+....+999等于多少？").stream();

        stream.subscribe(new Subscriber<ChatEvent>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                this.subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(ChatEvent event) {
                chunkCount[0]++;

                if (event.getError() == null) {
                    String text = event.getText();

                    if (event.is(ChatEventType.THINKING_DELTA)) {
                        if (text != null && !text.isEmpty()) {
                            thinkingChunkCount[0]++;
                            fullResponse.append(text);
                            thinkingResponse.append(text);
                            System.out.print("[思考块 #" + thinkingChunkCount[0] + "]");
                        }
                    } else if (event.is(ChatEventType.TEXT_DELTA)) {
                        if (text != null && !text.isEmpty()) {
                            resultChunkCount[0]++;
                            fullResponse.append(text);
                            resultResponse.append(text);
                            System.out.print(text);
                        }
                    }

                    if (event.is(ChatEventType.RESPONSE_END) && !finishedReported[0]) {
                        finishedReported[0] = true;

                        System.out.println("\n\n===========================================");
                        System.out.println("流式响应统计:");
                        System.out.println("   - 总块数: " + chunkCount[0]);
                        System.out.println("   - 思考块数: " + thinkingChunkCount[0]);
                        System.out.println("   - 回复块数: " + resultChunkCount[0]);

                        if (thinkingResponse.length() > 0) {
                            System.out.println("\n========== 思考过程 ==========");
                            System.out.println(thinkingResponse.toString());
                            System.out.println("==============================\n");
                        }

                        if (resultResponse.length() > 0) {
                            System.out.println("\n========== 最终回答 ==========");
                            System.out.println(resultResponse.toString());
                            System.out.println("=============================");
                        }

                        System.out.println("\n===========================================");
                    }
                }

                subscription.request(1);
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("\n流式错误: " + t.getMessage());
                latch.countDown();
            }

            @Override
            public void onComplete() {
                System.out.println("\n\n流式订阅完成");
                System.out.println("总消息块数: " + chunkCount[0]);
                latch.countDown();
            }
        });

        latch.await(60, TimeUnit.SECONDS);
    }
}
