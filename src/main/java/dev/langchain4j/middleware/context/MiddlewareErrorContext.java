package dev.langchain4j.middleware.context;

import java.util.Objects;

/**
 * Passed to {@link dev.langchain4j.middleware.AgentMiddleware#onError}. {@code phase} is typically a
 * {@link dev.langchain4j.middleware.MiddlewarePhase} constant, e.g. {@link dev.langchain4j.middleware.MiddlewarePhase#AGENT_CHAT}.
 */
public record MiddlewareErrorContext(Throwable error, String phase, Object detail) {
    public MiddlewareErrorContext {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(phase, "phase");
    }
}
