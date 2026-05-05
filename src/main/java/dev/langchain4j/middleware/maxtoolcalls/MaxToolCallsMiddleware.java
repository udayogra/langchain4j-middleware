package dev.langchain4j.middleware.maxtoolcalls;

import dev.langchain4j.middleware.AgentMiddleware;
import dev.langchain4j.middleware.AgentRunContext;
import dev.langchain4j.middleware.context.ToolCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enforces a per–agent-run cap on the total number of tool invocations (counter reset in {@link #beforeAgentRun},
 * limit enforced in {@link #wrapToolCall}).
 *
 * <p>When the cap is exceeded, either a synthetic tool result is returned (similar to LangChain.js
 * <a href="https://github.com/langchain-ai/langchainjs/blob/main/libs/langchain/src/agents/middleware/toolCallLimit.ts">{@code toolCallLimit}</a>
 * {@code exitBehavior: "continue"}) or an exception is thrown ({@code "error"}), per {@link #onLimit}.
 *
 * <p>Register with {@link dev.langchain4j.middleware.MiddlewareAi} or {@link dev.langchain4j.middleware.MiddlewareComposition}
 * with tool invocation hooks enabled (default when tools are non-empty).
 *
 * <p><strong>Concurrency:</strong> one instance is intended for one logical agent / client configuration. Concurrent
 * {@code chat} turns on the same middleware instance can interleave counts; use separate instances per client if needed.
 */
public final class MaxToolCallsMiddleware implements AgentMiddleware {

    private static final Logger log = LoggerFactory.getLogger(MaxToolCallsMiddleware.class);

    /** What to do when {@link #maxCalls} is exceeded on a tool invocation. */
    public enum OnLimit {
        /**
         * Do not run the tool; return a message string as the tool result so the model can read it and stop calling tools.
         */
        RETURN_MESSAGE,
        /** Do not run the tool; throw {@link MaxToolCallsExceededException}. */
        THROW
    }

    private final int maxCalls;
    private final OnLimit onLimit;
    private final AtomicInteger callCount = new AtomicInteger(0);

    /** Default: {@code maxCalls = 10}, {@link OnLimit#RETURN_MESSAGE}. */
    public MaxToolCallsMiddleware() {
        this(10, OnLimit.RETURN_MESSAGE);
    }

    /** Same as {@link #MaxToolCallsMiddleware(int, OnLimit)} with {@link OnLimit#RETURN_MESSAGE}. */
    public MaxToolCallsMiddleware(int maxCalls) {
        this(maxCalls, OnLimit.RETURN_MESSAGE);
    }

    /**
     * @param maxCalls maximum successful tool invocations allowed per agent run (after {@link #beforeAgentRun}); must be
     *     {@code >= 0}. When {@code 0}, every tool call is blocked.
     * @param onLimit {@link OnLimit#RETURN_MESSAGE} or {@link OnLimit#THROW}
     */
    public MaxToolCallsMiddleware(int maxCalls, OnLimit onLimit) {
        if (maxCalls < 0) {
            throw new IllegalArgumentException("maxCalls must be >= 0");
        }
        this.maxCalls = maxCalls;
        this.onLimit = Objects.requireNonNull(onLimit, "onLimit");
    }

    /** Configured cap (same value passed to the constructor). */
    public int maxCalls() {
        return maxCalls;
    }

    @Override
    public void beforeAgentRun(AgentRunContext context) {
        callCount.set(0);
    }

    @Override
    public String wrapToolCall(ToolCallContext context, ToolCallChain handler) {
        int n = callCount.incrementAndGet();
        if (n > maxCalls) {
            String toolName = toolLabel(context);
            String msg =
                    "MaxToolCallsMiddleware: tool call limit of "
                            + maxCalls
                            + " reached. Blocking '"
                            + toolName
                            + "' (call "
                            + n
                            + ").";
            log.warn(msg);
            if (onLimit == OnLimit.THROW) {
                throw new MaxToolCallsExceededException(maxCalls, n, toolName);
            }
            return msg;
        }
        return handler.execute(context);
    }

    private static String toolLabel(ToolCallContext context) {
        if (context == null || context.tool() == null) {
            return "unknown";
        }
        String n = context.tool().name();
        return (n == null || n.isBlank()) ? "unknown" : n;
    }
}
