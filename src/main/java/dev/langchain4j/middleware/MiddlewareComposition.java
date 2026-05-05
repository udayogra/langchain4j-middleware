package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared stacking rules for {@link AgentMiddleware} on your <strong>original</strong> {@link ChatModel} and tool map — the
 * same composition {@link MiddlewareAi} applies internally. Pass the provider model and middleware list here; static
 * {@code wrapChatModel} / {@code wrapTools} and optional per-call hooks are applied for you.
 *
 * <p>Use this when you already have {@code AiServices.builder(MyAssistant.class)} (or any full builder surface) and want
 * composed {@link #chatModel()} / {@link #tools()} plus {@link MiddlewareChatClient#wrap} around your assistant’s
 * {@code chat} method, without adopting {@link MiddlewareAi}. After {@link #compose}, keep configuring RAG, guardrails,
 * streaming model, etc. on {@code AiServices} exactly as without this library.
 *
 * <pre>{@code
 * var mw = List.of(new dev.langchain4j.middleware.logging.LoggingMiddleware("[app]", 200));
 * var stack = MiddlewareComposition.compose(chatModel, toolMap, mw, MiddlewareComposition.Options.defaults());
 * MyAssistant ai = AiServices.builder(MyAssistant.class)
 *     .chatModel(stack.chatModel())
 *     .tools(stack.tools())
 *     .chatMemoryProvider(myProvider)
 *     .build();
 * AgentChat gated = MiddlewareChatClient.wrap(ai::chat, mw);
 * }</pre>
 */
public final class MiddlewareComposition {

    private MiddlewareComposition() {}

    /**
     * Toggles matching {@link MiddlewareAi.Builder#llmInvocationHooks} and {@link MiddlewareAi.Builder#toolInvocationHooks}.
     */
    public record Options(boolean llmInvocationHooks, boolean toolInvocationHooks) {
        public static Options defaults() {
            return new Options(true, true);
        }
    }

    /**
     * Wrapped {@link ChatModel} and tool map to pass to {@link dev.langchain4j.service.AiServices}.
     *
     * @param chatModel composed model (static middleware stack, then optional per-{@code doChat} LLM hooks per
     *                  {@link Options#llmInvocationHooks})
     * @param tools     composed tool map (static per-tool wrappers, then optional per-{@code execute} hooks per
     *                  {@link Options#toolInvocationHooks}); empty if {@code baseTools} was empty
     */
    public record Result(ChatModel chatModel, Map<ToolSpecification, ToolExecutor> tools) {
        public Result {
            Objects.requireNonNull(chatModel, "chatModel");
            Objects.requireNonNull(tools, "tools");
        }
    }

    /**
     * @param baseModel   provider / raw model
     * @param baseTools   may be empty; copied defensively when non-empty
     * @param middlewares same list you pass to {@link MiddlewareChatClient#wrap} / {@link MiddlewareAi.Builder#middleware}
     */
    public static Result compose(
            ChatModel baseModel,
            Map<ToolSpecification, ToolExecutor> baseTools,
            List<AgentMiddleware> middlewares,
            Options options) {
        Objects.requireNonNull(baseModel, "baseModel");
        Objects.requireNonNull(middlewares, "middlewares");
        Objects.requireNonNull(options, "options");
        List<AgentMiddleware> mw = List.copyOf(middlewares);

        ChatModel model = AgentMiddlewareChain.wrapChatModel(baseModel, mw);
        if (options.llmInvocationHooks && !mw.isEmpty()) {
            model = AgentMiddlewareChain.wrapChatModelWithLlmHooks(model, mw);
        }

        Map<ToolSpecification, ToolExecutor> toolMap =
                baseTools == null || baseTools.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(baseTools));
        if (!toolMap.isEmpty()) {
            toolMap = AgentMiddlewareChain.wrapTools(toolMap, mw);
            if (options.toolInvocationHooks && !mw.isEmpty()) {
                toolMap = AgentMiddlewareChain.wrapToolsWithToolHooks(toolMap, mw);
            }
        }

        return new Result(model, toolMap);
    }

    /** Same as {@link #compose(ChatModel, Map, List, Options)} with {@link Options#defaults()}. */
    public static Result compose(
            ChatModel baseModel, Map<ToolSpecification, ToolExecutor> baseTools, List<AgentMiddleware> middlewares) {
        return compose(baseModel, baseTools, middlewares, Options.defaults());
    }

    /**
     * Chat-model half only (no tools): same stacking as {@link #compose} with an empty tool map. Use when you only need
     * middleware on the {@link ChatModel} (e.g. logging) and will wire tools elsewhere.
     */
    public static Result composeChatModel(ChatModel baseModel, List<AgentMiddleware> middlewares, Options options) {
        return compose(baseModel, Map.of(), middlewares, options);
    }

    /** Same as {@link #composeChatModel(ChatModel, List, Options)} with {@link Options#defaults()}. */
    public static Result composeChatModel(ChatModel baseModel, List<AgentMiddleware> middlewares) {
        return composeChatModel(baseModel, middlewares, Options.defaults());
    }
}
