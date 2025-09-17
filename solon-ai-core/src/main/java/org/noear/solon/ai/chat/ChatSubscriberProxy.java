/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.chat;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 订阅者代理（确保只触发一次 onSubscribe）
 *
 * @author noear
 * @since 3.2
 */
public class ChatSubscriberProxy<T> implements Subscriber<T> {
    private final Subscriber<T> subscriber;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    private ChatSubscriberProxy(Subscriber<T> subscriber) {
        this.subscriber = subscriber;
    }

    /**
     * 构建
     */
    public static <T> Subscriber<T> of(Subscriber<T> subscriber) {
        if (subscriber instanceof ChatSubscriberProxy) {
            return subscriber;
        } else {
            return new ChatSubscriberProxy<>(subscriber);
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(subscription);
        } else {
            subscription.request(1L);
        }
    }

    @Override
    public void onNext(T t) {
        subscriber.onNext(t);
    }

    @Override
    public void onError(Throwable throwable) {
        subscriber.onError(throwable);
    }

    @Override
    public void onComplete() {
        subscriber.onComplete();
    }
}