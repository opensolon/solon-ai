package org.noear.solon.ai.agent.team;

/**
 *
 * @author noear 2026/1/11 created
 *
 */
public class TeamOptionsAmend {
    private final TeamOptions options;

    public TeamOptionsAmend(TeamOptions options) {
        this.options = options;
    }

    public TeamOptionsAmend maxTotalIterations(int maxTotalIterations) {
        options.setMaxTotalIterations(maxTotalIterations);
        return this;
    }

    public TeamOptionsAmend retryConfig(int maxRetries, long retryDelayMs) {
        options.setRetryConfig(maxRetries, retryDelayMs);
        return this;
    }

    public TeamOptionsAmend interceptorAdd(TeamInterceptor interceptor) {
        options.addInterceptor(interceptor, 0);
        return this;
    }

    public TeamOptionsAmend interceptorAdd(TeamInterceptor interceptor, int index) {
        options.addInterceptor(interceptor, index);
        return this;
    }
}
