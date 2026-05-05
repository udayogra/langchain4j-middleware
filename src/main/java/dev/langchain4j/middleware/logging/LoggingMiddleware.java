package dev.langchain4j.middleware.logging;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.middleware.AgentMiddleware;
import dev.langchain4j.middleware.AgentRunContext;
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.MiddlewareComposition;
import dev.langchain4j.middleware.context.MiddlewareErrorContext;
import dev.langchain4j.middleware.context.ModelCallAfterContext;
import dev.langchain4j.middleware.context.ModelCallContext;
import dev.langchain4j.middleware.context.ToolCallAfterContext;
import dev.langchain4j.middleware.context.ToolCallContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Logs AI lifecycle events at SLF4J {@code INFO}, failures at {@code ERROR}, with optional prefix and preview length
 * ({@link #beforeAgentRun} / {@link #afterAgentRun}, {@link #beforeLLMCall} / {@link #afterLLMCall},
 * {@link #beforeToolCall} / {@link #afterToolCall}, {@link #onError}).
 *
 * <p>LLM and tool hooks run only when the {@link ChatModel} / tool map are composed with
 * {@link MiddlewareComposition} (with {@link MiddlewareComposition.Options#llmInvocationHooks} /
 * {@link MiddlewareComposition.Options#toolInvocationHooks}) or {@link MiddlewareAi}
 * (e.g. {@link MiddlewareAi} defaults). Static {@link AgentMiddleware#wrapChatModel} /
 * {@link AgentMiddleware#wrapToolExecutor} remain default pass-through.
 *
 * <p>Add an SLF4J binding (e.g. {@code slf4j-simple}, Logback) at runtime so log output appears where you expect.
 */
public final class LoggingMiddleware implements AgentMiddleware {

    private static final Logger log = LoggerFactory.getLogger(LoggingMiddleware.class);
    private static final int HARD_CAP = 8192;

    private final String prefix;
    private final int previewChars;

    public LoggingMiddleware() {
        this("[AI Middleware]", 120);
    }

    public LoggingMiddleware(String prefix, int previewChars) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (previewChars < 1) {
            throw new IllegalArgumentException("previewChars must be >= 1");
        }
        this.previewChars = previewChars;
    }

    @Override
    public void beforeAgentRun(AgentRunContext context) {
        log.info("{} Agent run starting | memoryId={} | userMessage: {}", prefix, context.memoryId(), preview(context.userMessage()));
    }

    @Override
    public void afterAgentRun(AgentRunContext context, String assistantReply) {
        log.info(
                "{} Agent run complete | memoryId={} | response: {}",
                prefix,
                context.memoryId(),
                preview(assistantReply));
    }

    @Override
    public void beforeLLMCall(ModelCallContext context) {
        log.info("{} LLM call starting | model: {}", prefix, modelLabel(context.request()));
    }

    @Override
    public void afterLLMCall(ModelCallAfterContext context) {
        log.info("{} LLM call complete | model: {}", prefix, modelLabel(context.request()));
    }

    @Override
    public void beforeToolCall(ToolCallContext context) {
        log.info("{} Tool call starting | tool: {}", prefix, toolName(context.tool()));
    }

    @Override
    public void afterToolCall(ToolCallAfterContext context) {
        log.info(
                "{} Tool call complete | tool: {} | result: {}",
                prefix,
                toolName(context.tool()),
                preview(context.result()));
    }

    @Override
    public void onError(MiddlewareErrorContext context) {
        Throwable error = context.error();
        String phase = context.phase();
        Object detail = context.detail();
        if (detail instanceof AgentRunContext agentCtx) {
            log.error(
                    "{} Error in phase '{}' | memoryId={} | userMessage: {}",
                    prefix,
                    phase,
                    agentCtx.memoryId(),
                    preview(agentCtx.userMessage()),
                    error);
        } else if (detail instanceof ModelCallContext mc) {
            log.error("{} Error in phase '{}' | model: {}", prefix, phase, modelLabel(mc.request()), error);
        } else if (detail instanceof ModelCallAfterContext ma) {
            log.error("{} Error in phase '{}' | model: {}", prefix, phase, modelLabel(ma.request()), error);
        } else if (detail instanceof ToolCallContext tc) {
            log.error("{} Error in phase '{}' | tool: {}", prefix, phase, toolName(tc.tool()), error);
        } else if (detail instanceof ToolCallAfterContext ta) {
            log.error("{} Error in phase '{}' | tool: {}", prefix, phase, toolName(ta.tool()), error);
        } else {
            String msg = error.getMessage() != null ? error.getMessage() : error.toString();
            log.error("{} Error in phase '{}': {}", prefix, phase, msg, error);
        }
    }

    private static String modelLabel(ChatRequest request) {
        String n = request.modelName();
        return (n == null || n.isBlank()) ? "unknown" : n;
    }

    private static String toolName(ToolSpecification tool) {
        if (tool == null) {
            return "unknown";
        }
        String n = tool.name();
        return (n == null || n.isBlank()) ? "unknown" : n;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String t = text.length() > HARD_CAP ? text.substring(0, HARD_CAP) + "..." : text;
        if (t.length() <= previewChars) {
            return t;
        }
        return t.substring(0, previewChars) + "...";
    }
}
