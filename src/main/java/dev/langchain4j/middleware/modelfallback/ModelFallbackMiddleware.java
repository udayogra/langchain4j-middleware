package dev.langchain4j.middleware.modelfallback;

import dev.langchain4j.middleware.AgentMiddleware;
import dev.langchain4j.middleware.ChatRequestNormalizer;
import dev.langchain4j.middleware.context.ModelCallContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * On LLM failure, tries additional {@link ChatModel} instances in order, aligned with LangChain.js
 * <a href="https://github.com/langchain-ai/langchainjs/blob/main/libs/langchain/src/agents/middleware/modelFallback.ts">{@code modelFallbackMiddleware}</a>.
 *
 * <p>The <strong>primary</strong> model is whatever you pass to {@link dev.langchain4j.middleware.MiddlewareAi} /
 * {@link dev.langchain4j.middleware.MiddlewareComposition}; this middleware only receives the ordered <strong>fallback</strong>
 * {@link ChatModel} instances (no string identifiers or factory indirection).
 *
 * <p>Fallback attempts call {@link ChatModel#doChat} directly on each fallback with {@link ChatRequestNormalizer#forDelegate}
 * so vendor-specific request parameters stay valid. Inner {@link #wrapLLMCall} middleware <em>inside</em> this one (closer
 * to the primary delegate) does not run on those fallback {@code doChat} invocations; place {@code ModelFallbackMiddleware}
 * outermost in your list if that matters.
 *
 * <p>If every fallback also fails, the <strong>last</strong> fallback error is thrown (LangChain.js behavior for the
 * final catch).
 */
public final class ModelFallbackMiddleware implements AgentMiddleware {

    private static final Logger log = LoggerFactory.getLogger(ModelFallbackMiddleware.class);

    private final List<ChatModel> fallbackModels;

    /**
     * @param first first fallback when the primary model throws
     * @param more additional fallbacks, tried in order after {@code first}
     */
    public ModelFallbackMiddleware(ChatModel first, ChatModel... more) {
        Objects.requireNonNull(first, "first");
        List<ChatModel> list = new ArrayList<>(1 + more.length);
        list.add(first);
        for (ChatModel m : more) {
            list.add(Objects.requireNonNull(m, "fallback model"));
        }
        this.fallbackModels = List.copyOf(list);
    }

    /**
     * @param fallbackModels non-empty ordered list of fallback models (each non-null)
     */
    public ModelFallbackMiddleware(List<ChatModel> fallbackModels) {
        Objects.requireNonNull(fallbackModels, "fallbackModels");
        if (fallbackModels.isEmpty()) {
            throw new IllegalArgumentException("At least one fallback ChatModel is required");
        }
        List<ChatModel> copy = new ArrayList<>(fallbackModels.size());
        for (ChatModel m : fallbackModels) {
            copy.add(Objects.requireNonNull(m, "fallback model"));
        }
        this.fallbackModels = List.copyOf(copy);
    }

    @Override
    public ChatResponse wrapLLMCall(ModelCallContext context, LlmCallChain handler) {
        try {
            return handler.proceed(context);
        } catch (Throwable primaryError) {
            log.warn(
                    "ModelFallbackMiddleware: primary model failed ({}). Trying {} fallback model(s)…",
                    primaryError.getMessage() != null ? primaryError.getMessage() : primaryError.getClass().getSimpleName(),
                    fallbackModels.size());
            Throwable last = primaryError;
            int index = 0;
            for (ChatModel fallback : fallbackModels) {
                index++;
                ChatRequest request = ChatRequestNormalizer.forDelegate(fallback, context.request());
                try {
                    ChatResponse out = fallback.doChat(request);
                    log.info("ModelFallbackMiddleware: fallback #{} ({}) succeeded.", index, label(fallback));
                    return out;
                } catch (Throwable e) {
                    last = e;
                    log.warn(
                            "ModelFallbackMiddleware: fallback #{} ({}) failed: {}",
                            index,
                            label(fallback),
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
            throw sneakyRethrow(last);
        }
    }

    private static String label(ChatModel model) {
        if (model == null) {
            return "null";
        }
        String simple = model.getClass().getSimpleName();
        return simple.isBlank() ? model.getClass().getName() : simple;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyRethrow(Throwable t) throws T {
        throw (T) t;
    }
}
