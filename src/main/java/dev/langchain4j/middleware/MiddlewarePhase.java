package dev.langchain4j.middleware;

/**
 * {@link dev.langchain4j.middleware.context.MiddlewareErrorContext#phase()} string constants used by this library.
 * Custom harnesses may define additional phase labels if they extend error reporting.
 */
public final class MiddlewarePhase {

    public static final String BEFORE_AGENT_RUN = "beforeAgentRun";
    public static final String AFTER_AGENT_RUN = "afterAgentRun";
    public static final String BEFORE_LLM_CALL = "beforeLLMCall";
    public static final String AFTER_LLM_CALL = "afterLLMCall";
    public static final String WRAP_LLM_CALL = "wrapLLMCall";
    public static final String BEFORE_TOOL_CALL = "beforeToolCall";
    public static final String AFTER_TOOL_CALL = "afterToolCall";
    public static final String WRAP_TOOL_CALL = "wrapToolCall";
    /** Thrown from outer {@link AgentChat#chat} when no finer-grained phase is known. */
    public static final String AGENT_CHAT = "AgentChat.chat";

    private MiddlewarePhase() {}
}
