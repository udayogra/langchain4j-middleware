package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.middleware.context.MiddlewareErrorContext;
import dev.langchain4j.middleware.context.ModelCallAfterContext;
import dev.langchain4j.middleware.context.ModelCallContext;
import dev.langchain4j.middleware.context.ToolCallAfterContext;
import dev.langchain4j.middleware.context.ToolCallContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal wiring: composes {@link AgentMiddleware} for {@link AgentChat}, {@link ChatModel}, and tools. Application code
 * should use {@link MiddlewareAi}, {@link MiddlewareComposition}, or {@link MiddlewareChatClient#wrap} — pass the
 * <strong>original</strong> {@link ChatModel} and middleware list there; wrapping happens inside those APIs.
 */
final class AgentMiddlewareChain {

    private AgentMiddlewareChain() {}

    private static List<AgentMiddleware> copyMiddlewares(List<AgentMiddleware> middlewares) {
        return List.copyOf(middlewares);
    }

    private static void notifyOnError(List<AgentMiddleware> middlewares, Throwable error, String phase, Object detail) {
        MiddlewareErrorContext ctx = new MiddlewareErrorContext(error, phase, detail);
        for (AgentMiddleware m : middlewares) {
            m.onError(ctx);
        }
    }

    /**
     * When {@code middlewares} is empty, returns {@code delegate} unchanged. Otherwise returns a wrapper that runs
     * {@link AgentMiddleware#beforeAgentRun}, {@link AgentMiddleware#afterAgentRun}, and {@link AgentMiddleware#onError}.
     */
    static AgentChat wrapAgent(AgentChat delegate, List<AgentMiddleware> middlewares) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(middlewares, "middlewares");
        List<AgentMiddleware> copy = copyMiddlewares(middlewares);
        if (copy.isEmpty()) {
            return delegate;
        }
        return new Wrapped(delegate, copy);
    }

    /** Convenience: {@code wrapAgent(delegate, List.of(first, rest...))}. */
    static AgentChat wrapAgent(AgentChat delegate, AgentMiddleware first, AgentMiddleware... rest) {
        Objects.requireNonNull(first, "first");
        List<AgentMiddleware> list = new ArrayList<>(1 + rest.length);
        list.add(first);
        list.addAll(Arrays.asList(rest));
        return wrapAgent(delegate, list);
    }

    /**
     * Applies {@link AgentMiddleware#wrapChatModel} for each entry in order: the <strong>first</strong> list element is the
     * <strong>outermost</strong> {@link ChatModel}.
     */
    static ChatModel wrapChatModel(ChatModel base, List<AgentMiddleware> middlewares) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(middlewares, "middlewares");
        List<AgentMiddleware> copy = copyMiddlewares(middlewares);
        ChatModel model = base;
        for (int i = copy.size() - 1; i >= 0; i--) {
            model = copy.get(i).wrapChatModel(model);
        }
        return model;
    }

    /**
     * Wraps {@code base} so each {@link ChatModel#doChat} runs {@link AgentMiddleware#beforeLLMCall},
     * {@link AgentMiddleware#wrapLLMCall}, and {@link AgentMiddleware#afterLLMCall} (after in reverse list order, same as
     * {@link #wrapAgent}’s {@code afterAgentRun}).
     */
    static ChatModel wrapChatModelWithLlmHooks(ChatModel base, List<AgentMiddleware> middlewares) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(middlewares, "middlewares");
        List<AgentMiddleware> copy = copyMiddlewares(middlewares);
        if (copy.isEmpty()) {
            return base;
        }
        return new HookedChatModel(base, copy);
    }

    /**
     * Applies {@link AgentMiddleware#wrapToolExecutor} to every map entry. Map keys are preserved; wrapping order matches
     * {@link #wrapChatModel}.
     */
    static Map<ToolSpecification, ToolExecutor> wrapTools(
            Map<ToolSpecification, ToolExecutor> tools, List<AgentMiddleware> middlewares) {
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(middlewares, "middlewares");
        List<AgentMiddleware> copy = copyMiddlewares(middlewares);
        if (copy.isEmpty()) {
            return tools;
        }
        Map<ToolSpecification, ToolExecutor> out = new LinkedHashMap<>();
        for (Map.Entry<ToolSpecification, ToolExecutor> e : tools.entrySet()) {
            ToolExecutor exec = e.getValue();
            for (int i = copy.size() - 1; i >= 0; i--) {
                exec = copy.get(i).wrapToolExecutor(e.getKey(), exec);
            }
            out.put(e.getKey(), exec);
        }
        return out;
    }

    /**
     * Like {@link #wrapTools} but each {@link ToolExecutor#execute} also runs {@link AgentMiddleware#beforeToolCall},
     * {@link AgentMiddleware#wrapToolCall}, and {@link AgentMiddleware#afterToolCall}.
     */
    static Map<ToolSpecification, ToolExecutor> wrapToolsWithToolHooks(
            Map<ToolSpecification, ToolExecutor> tools, List<AgentMiddleware> middlewares) {
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(middlewares, "middlewares");
        List<AgentMiddleware> copy = copyMiddlewares(middlewares);
        if (copy.isEmpty()) {
            return tools;
        }
        Map<ToolSpecification, ToolExecutor> out = new LinkedHashMap<>();
        for (Map.Entry<ToolSpecification, ToolExecutor> e : tools.entrySet()) {
            out.put(e.getKey(), new HookedToolExecutor(e.getValue(), e.getKey(), copy));
        }
        return out;
    }

    private static final class Wrapped implements AgentChat {
        private final AgentChat delegate;
        private final List<AgentMiddleware> middlewares;

        Wrapped(AgentChat delegate, List<AgentMiddleware> middlewares) {
            this.delegate = delegate;
            this.middlewares = middlewares;
        }

        @Override
        public String chat(String memoryId, String userMessage) {
            AgentRunContext ctx = new AgentRunContext(memoryId, userMessage);
            for (AgentMiddleware m : middlewares) {
                m.beforeAgentRun(ctx);
            }
            try {
                String reply = delegate.chat(memoryId, userMessage);
                for (int i = middlewares.size() - 1; i >= 0; i--) {
                    middlewares.get(i).afterAgentRun(ctx, reply);
                }
                return reply;
            } catch (Throwable t) {
                notifyOnError(middlewares, t, MiddlewarePhase.AGENT_CHAT, ctx);
                throw t;
            }
        }
    }

    private static final class HookedChatModel implements ChatModel {

        private final ChatModel delegate;
        private final List<AgentMiddleware> middlewares;

        HookedChatModel(ChatModel delegate, List<AgentMiddleware> middlewares) {
            this.delegate = delegate;
            this.middlewares = middlewares;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            ChatRequest effectiveRequest = ChatRequestNormalizer.forDelegate(delegate, request);
            ModelCallContext ctx = new ModelCallContext(delegate, effectiveRequest);
            try {
                for (AgentMiddleware m : middlewares) {
                    m.beforeLLMCall(ctx);
                }
            } catch (Throwable t) {
                notifyOnError(middlewares, t, MiddlewarePhase.BEFORE_LLM_CALL, ctx);
                throw t;
            }
            ChatResponse response;
            try {
                AgentMiddleware.LlmCallChain inner = c -> delegate.doChat(c.request());
                AgentMiddleware.LlmCallChain composed = inner;
                for (int i = middlewares.size() - 1; i >= 0; i--) {
                    AgentMiddleware m = middlewares.get(i);
                    AgentMiddleware.LlmCallChain next = composed;
                    composed = c -> m.wrapLLMCall(c, next);
                }
                response = composed.proceed(ctx);
            } catch (Throwable t) {
                notifyOnError(middlewares, t, MiddlewarePhase.WRAP_LLM_CALL, ctx);
                throw t;
            }
            ModelCallAfterContext after = new ModelCallAfterContext(delegate, effectiveRequest, response);
            try {
                for (int i = middlewares.size() - 1; i >= 0; i--) {
                    middlewares.get(i).afterLLMCall(after);
                }
            } catch (Throwable t) {
                notifyOnError(middlewares, t, MiddlewarePhase.AFTER_LLM_CALL, after);
                throw t;
            }
            return response;
        }
    }

    private static final class HookedToolExecutor implements ToolExecutor {

        private final ToolExecutor delegate;
        private final ToolSpecification tool;
        private final List<AgentMiddleware> middlewares;

        HookedToolExecutor(ToolExecutor delegate, ToolSpecification tool, List<AgentMiddleware> middlewares) {
            this.delegate = delegate;
            this.tool = tool;
            this.middlewares = middlewares;
        }

        @Override
        public String execute(dev.langchain4j.agent.tool.ToolExecutionRequest toolRequest, Object memoryId) {
            ToolCallContext ctx = new ToolCallContext(tool, toolRequest, memoryId);
            try {
                for (AgentMiddleware m : middlewares) {
                    m.beforeToolCall(ctx);
                }
            } catch (Throwable t) {
                notifyOnError(middlewares, t, MiddlewarePhase.BEFORE_TOOL_CALL, ctx);
                throw t;
            }
            String result;
            try {
                AgentMiddleware.ToolCallChain inner = c -> delegate.execute(c.toolRequest(), c.memoryId());
                AgentMiddleware.ToolCallChain composed = inner;
                for (int i = middlewares.size() - 1; i >= 0; i--) {
                    AgentMiddleware m = middlewares.get(i);
                    AgentMiddleware.ToolCallChain next = composed;
                    composed = c -> m.wrapToolCall(c, next);
                }
                result = composed.execute(ctx);
            } catch (Throwable t) {
                notifyOnError(middlewares, t, MiddlewarePhase.WRAP_TOOL_CALL, ctx);
                throw t;
            }
            ToolCallAfterContext after = new ToolCallAfterContext(tool, toolRequest, memoryId, result);
            try {
                for (int i = middlewares.size() - 1; i >= 0; i--) {
                    middlewares.get(i).afterToolCall(after);
                }
            } catch (Throwable t) {
                notifyOnError(middlewares, t, MiddlewarePhase.AFTER_TOOL_CALL, after);
                throw t;
            }
            return result;
        }
    }
}
