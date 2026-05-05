package dev.langchain4j.middleware.retry;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.middleware.AgentMiddleware;
import dev.langchain4j.middleware.context.ModelCallContext;
import dev.langchain4j.middleware.context.ToolCallContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Retries failed LLM and tool invocations with exponential backoff via {@link #wrapLLMCall} and {@link #wrapToolCall}.
 *
 * <p>Register with {@link dev.langchain4j.middleware.MiddlewareAi} or {@link dev.langchain4j.middleware.MiddlewareComposition}
 * when {@code llmInvocationHooks} / {@code toolInvocationHooks} are enabled so {@code wrap*} hooks run.
 */
public final class RetryMiddleware implements AgentMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RetryMiddleware.class);

    private final int maxRetries;
    private final long initialDelayMillis;
    private final double backoffMultiplier;
    private final long maxDelayMillis;
    private final Set<String> nonRetryableSimpleNames;

    /** Defaults: maxRetries 3, initial delay 1s, multiplier 2, max delay 30s, non-retryable {@code IllegalArgumentException}. */
    public RetryMiddleware() {
        this(3, 1000L, 2.0, 30_000L, "IllegalArgumentException");
    }

    /**
     * @param maxRetries max retry attempts after the first failure (at most {@code maxRetries + 1} attempts total)
     * @param initialDelayMillis sleep before the first retry
     * @param backoffMultiplier applied after each failed attempt (delay capped by {@code maxDelayMillis})
     * @param maxDelayMillis upper bound for sleep between attempts
     * @param nonRetryableTypesCommaSeparated exception {@link Class#getSimpleName() simple names}, comma-separated
     *     (case-insensitive); those throw immediately without retry
     */
    public RetryMiddleware(
            int maxRetries,
            long initialDelayMillis,
            double backoffMultiplier,
            long maxDelayMillis,
            String nonRetryableTypesCommaSeparated) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (initialDelayMillis < 0) {
            throw new IllegalArgumentException("initialDelayMillis must be >= 0");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
        if (maxDelayMillis < 0) {
            throw new IllegalArgumentException("maxDelayMillis must be >= 0");
        }
        this.maxRetries = maxRetries;
        this.initialDelayMillis = initialDelayMillis;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelayMillis = maxDelayMillis;
        this.nonRetryableSimpleNames = parseNonRetryable(nonRetryableTypesCommaSeparated);
    }

    private static Set<String> parseNonRetryable(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t.toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }

    @Override
    public ChatResponse wrapLLMCall(ModelCallContext context, LlmCallChain handler) {
        return executeWithRetry("LLM", () -> handler.proceed(context));
    }

    @Override
    public String wrapToolCall(ToolCallContext context, ToolCallChain handler) {
        String toolLabel = toolLabel(context.tool());
        return executeWithRetry("tool:" + toolLabel, () -> handler.execute(context));
    }

    private static String toolLabel(ToolSpecification tool) {
        if (tool == null) {
            return "unknown";
        }
        String n = tool.name();
        return (n == null || n.isBlank()) ? "unknown" : n;
    }

    private <T> T executeWithRetry(String label, ThrowingSupplier<T> supplier) {
        int attempt = 0;
        long delay = initialDelayMillis;
        while (true) {
            try {
                return supplier.get();
            } catch (Throwable e) {
                if (!isRetryable(e)) {
                    throw sneakyRethrow(e);
                }
                attempt++;
                if (attempt > maxRetries) {
                    log.error(
                            "RetryMiddleware: {} failed after {} retries. Last error: {}",
                            label,
                            maxRetries,
                            e.getMessage());
                    throw sneakyRethrow(e);
                }
                log.warn(
                        "RetryMiddleware: {} attempt {} failed ({}). Retrying in {} ms…",
                        label,
                        attempt,
                        e.getMessage(),
                        delay);
                sleepUnchecked(delay);
                delay = Math.min((long) (delay * backoffMultiplier), maxDelayMillis);
            }
        }
    }

    private boolean isRetryable(Throwable e) {
        String simple = e.getClass().getSimpleName();
        return simple == null || !nonRetryableSimpleNames.contains(simple.toLowerCase(Locale.ROOT));
    }

    private static void sleepUnchecked(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("RetryMiddleware sleep interrupted", ie);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyRethrow(Throwable t) throws T {
        throw (T) t;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }
}
