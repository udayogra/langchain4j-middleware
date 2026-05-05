package dev.langchain4j.middleware;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Optional wrapper type for {@link AgentChat} when you compose middleware around an existing assistant. For
 * {@link MiddlewareAi}, {@link MiddlewareAi.Builder#build()} returns {@link AgentChat} directly (no extra delegate).
 *
 * <p><strong>Bring your own {@code AiServices}:</strong> configure and {@code build()} any assistant interface whose
 * {@code chat(memoryId, userMessage)} matches {@link AgentChat} (typical {@code @MemoryId} + {@code @UserMessage} on
 * LangChain4j {@link dev.langchain4j.service.AiServices}), then wrap it here. Apply {@link MiddlewareComposition#compose}
 * to the {@link dev.langchain4j.model.chat.ChatModel} / tool map <em>before</em> {@code AiServices.builder(...)} if you
 * need model/tool middleware; this class only adds the outer agent-turn chain around {@code chat}.
 */
public final class MiddlewareChatClient implements AgentChat {

    private final AgentChat delegate;

    MiddlewareChatClient(AgentChat delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Wrap an existing {@link AgentChat} (e.g. {@code myAssistant::chat} from a fully configured
     * {@link dev.langchain4j.service.AiServices} instance) with the same agent middleware semantics as
     * {@link MiddlewareAi}.
     */
    public static MiddlewareChatClient wrap(AgentChat inner, List<AgentMiddleware> middlewares) {
        return new MiddlewareChatClient(AgentMiddlewareChain.wrapAgent(inner, middlewares));
    }

    /** Convenience: {@code wrap(inner, List.of(first, rest...))}. */
    public static MiddlewareChatClient wrap(AgentChat inner, AgentMiddleware first, AgentMiddleware... rest) {
        Objects.requireNonNull(first, "first");
        List<AgentMiddleware> list = new ArrayList<>(1 + rest.length);
        list.add(first);
        list.addAll(Arrays.asList(rest));
        return wrap(inner, list);
    }

    @Override
    public String chat(String memoryId, String userMessage) {
        return delegate.chat(memoryId, userMessage);
    }
}
