package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.middleware.context.MiddlewareErrorContext;
import dev.langchain4j.middleware.context.ModelCallAfterContext;
import dev.langchain4j.middleware.context.ModelCallContext;
import dev.langchain4j.middleware.context.ToolCallAfterContext;
import dev.langchain4j.middleware.context.ToolCallContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.Objects;

/**
 * Single middleware contract: hooks for agent turns, LLM calls, tool calls, and errors, plus {@link #wrapLLMCall} /
 * {@link #wrapToolCall} for interception, and LangChain4j-style {@link #wrapChatModel} / per-tool
 * {@link #wrapToolExecutor(ToolSpecification, ToolExecutor)} for static composition when you pass middleware to
 * {@link MiddlewareComposition#compose} or {@link MiddlewareAi} (tools are wrapped internally).
 */
public interface AgentMiddleware {

    /** Invoked before each outer agent {@code chat} turn. */
    default void beforeAgentRun(AgentRunContext context) {}

    /** Invoked after each outer agent {@code chat} turn. */
    default void afterAgentRun(AgentRunContext context, String assistantReply) {}

    /** Invoked before each composed {@link ChatModel} call (when LLM hooks are enabled). */
    default void beforeLLMCall(ModelCallContext context) {}

    /** Invoked after each composed {@link ChatModel} call (when LLM hooks are enabled). */
    default void afterLLMCall(ModelCallAfterContext context) {}

    /** Invoked before each tool {@code execute} (when tool hooks are enabled). */
    default void beforeToolCall(ToolCallContext context) {}

    /** Invoked after each tool {@code execute} (when tool hooks are enabled). */
    default void afterToolCall(ToolCallAfterContext context) {}

    /** Invoked when an error occurs in an instrumented phase. */
    default void onError(MiddlewareErrorContext context) {}

    /**
     * Intercept one model call. Default runs {@code handler} once (no interception).
     *
     * @see MiddlewareComposition.Options#llmInvocationHooks
     */
    default ChatResponse wrapLLMCall(ModelCallContext context, LlmCallChain handler) {
        return handler.proceed(context);
    }

    /**
     * Intercept one tool execution. Default runs {@code handler} once.
     *
     * @see MiddlewareComposition.Options#toolInvocationHooks
     */
    default String wrapToolCall(ToolCallContext context, ToolCallChain handler) {
        return handler.execute(context);
    }

    /**
     * Static composition: wrap the whole {@link ChatModel} (e.g. logging or request rewriting). First list entry is outermost when
     * composing via {@link MiddlewareComposition#compose} or {@link MiddlewareAi}.
     */
    default ChatModel wrapChatModel(ChatModel delegate) {
        return Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Static composition: wrap one tool executor. First list entry is outermost when composing via
     * {@link MiddlewareComposition#compose} (tool map half).
     */
    default ToolExecutor wrapToolExecutor(ToolSpecification spec, ToolExecutor delegate) {
        return Objects.requireNonNull(delegate, "delegate");
    }

    /** Callback for {@link #wrapLLMCall(ModelCallContext, LlmCallChain)}. */
    @FunctionalInterface
    interface LlmCallChain {
        ChatResponse proceed(ModelCallContext context);
    }

    /** Callback for {@link #wrapToolCall(ToolCallContext, ToolCallChain)}. */
    @FunctionalInterface
    interface ToolCallChain {
        String execute(ToolCallContext context);
    }
}
