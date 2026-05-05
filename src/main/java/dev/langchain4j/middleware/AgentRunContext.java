package dev.langchain4j.middleware;

import java.util.Objects;

/**
 * Immutable context for one {@link AgentChat#chat(String, String)} invocation (passed to {@code beforeAgentRun} /
 * {@code afterAgentRun} hooks).
 *
 * @param memoryId    Session / memory key (same idea as {@code @MemoryId}).
 * @param userMessage User text for this turn; {@code null} is normalized to empty string.
 */
public record AgentRunContext(String memoryId, String userMessage) {
    public AgentRunContext {
        Objects.requireNonNull(memoryId, "memoryId");
        userMessage = userMessage == null ? "" : userMessage;
    }
}
