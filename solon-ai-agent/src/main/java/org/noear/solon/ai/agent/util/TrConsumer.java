package org.noear.solon.ai.agent.util;

import java.util.Objects;

/**
 *
 * @author noear 2025/12/30 created
 *
 */
@FunctionalInterface
public interface TrConsumer<T, U, X> {
    void accept(T t, U u, X x);

    default TrConsumer<T, U, X> andThen(TrConsumer<? super T, ? super U, ? super X> after) {
        Objects.requireNonNull(after);

        return (l, r, x) -> {
            accept(l, r, x);
            after.accept(l, r, x);
        };
    }
}
