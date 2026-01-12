package features.ai.chat;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;
import org.noear.solon.test.SolonTest;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Arrays;
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
        System.out.println(resp.getChoices());
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

        Publisher<ChatResponse> stream = chatModel.prompt("请问1+2+3+4+5+....+999等于多少？").stream();

        stream.subscribe(new Subscriber<ChatResponse>() {
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                this.subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(ChatResponse response) {
                chunkCount[0]++;

                String responseData = response.getResponseData();
                if (responseData != null && responseData.contains("thought")) {
                    System.err.println("RAW RESPONSE: " + responseData.substring(0, Math.min(500, responseData.length())));
                }

                System.err.println("TEST DEBUG onNext: response=" + (response != null ? response.getClass().getSimpleName() : "null"));
                if (response != null && response instanceof ChatResponseDefault) {
                    ChatResponseDefault respDefault = (ChatResponseDefault) response;
                    System.err.println("  respDefault.in_thinking=" + respDefault.in_thinking);
                }

                if (response != null && response.getError() == null) {
                    if (response.getChoices().size() > 0) {
                        AssistantMessage msg = response.getChoices().get(0).getMessage();
                        String content = msg.getContent();
                        boolean isThinking = msg.isThinking();

                        System.err.println("TEST DEBUG: isThinking=" + isThinking + ", contentLength=" + (content != null ? content.length() : "null"));
                        if (content != null) {
                            String preview = content.replace("\n", "\\n").replace("\r", "\\r");
                            if (!preview.isEmpty()) {
                                System.err.println("  preview: " + preview);
                            } else {
                                System.err.println("  content is whitespace only, chars: " + Arrays.toString(content.toCharArray()));
                            }
                        } else {
                            System.err.println("  content is null!");
                        }

                        if (content != null && !content.isEmpty()) {
                            fullResponse.append(content);

                            if (isThinking) {
                                thinkingChunkCount[0]++;
                                thinkingResponse.append(content);
                                System.out.print("[思考块 #" + thinkingChunkCount[0] + "]");
                            } else {
                                resultChunkCount[0]++;
                                resultResponse.append(content);
                                System.out.print(content);
                            }
                        }
                    }

                    if (response.isFinished() && !finishedReported[0]) {
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
