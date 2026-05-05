package dev.langchain4j.middleware.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.Objects;

/** Context for {@link dev.langchain4j.middleware.AgentMiddleware#beforeToolCall} and {@code wrapToolCall}. */
public record ToolCallContext(
        ToolSpecification tool, ToolExecutionRequest toolRequest, Object memoryId) {
    public ToolCallContext {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(toolRequest, "toolRequest");
    }
}
