package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.middleware.summarization.SummarizationMiddleware;
import dev.langchain4j.middleware.summarization.SummarizingChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single entry point: configure {@link ChatModel}, middleware, memory, map-based tools, optional RAG, then
 * {@link #build()} an {@link AgentChat}. All values are explicit — {@link AiServices} does not expose getters, so this
 * type carries what you want applied.
 *
 * <p>For a custom assistant interface or extra {@link AiServices} options (guardrails, listeners, …), use
 * {@link MiddlewareComposition#compose} + your own {@link AiServices#builder} + {@link MiddlewareChatClient#wrap} — pass your
 * <strong>original</strong> {@link ChatModel} and middleware list to {@link MiddlewareComposition} / this builder; wrapping is
 * internal.
 *
 * <pre>{@code
 * AgentChat client = MiddlewareAi.create(chatModel)
 *     .middleware(new dev.langchain4j.middleware.logging.LoggingMiddleware("[app]", 200))
 *     .contentRetriever(myRetriever)
 *     .tools(toolMap)
 *     .build();
 * }</pre>
 *
 * <p>If the middleware list contains {@link SummarizationMiddleware}, {@code build()} removes it from the LLM chain
 * and wraps {@link ChatMemory} / {@link ChatMemoryProvider} with {@link SummarizingChatMemory} using that instance's
 * settings (persisted summarization). Use {@link Builder#summarizationDelegateMaxMessages(int)} when relying on the
 * default memory provider so the inner window is large enough before compaction.
 */
public final class MiddlewareAi {

    private static final Logger log = LoggerFactory.getLogger(MiddlewareAi.class);

    private MiddlewareAi() {}

    /** Same as {@link #builder(ChatModel)} — pick whichever name reads better at the call site. */
    public static Builder create(ChatModel chatModel) {
        return builder(chatModel);
    }

    public static Builder builder(ChatModel chatModel) {
        return new Builder(chatModel);
    }

    public static final class Builder {

        private final ChatModel baseChatModel;
        private final List<AgentMiddleware> middlewares = new ArrayList<>();
        private ChatMemory chatMemory;
        private ChatMemoryProvider chatMemoryProvider;
        private boolean explicitChatMemoryProvider;
        private int summarizationDelegateMaxMessages = 100;
        private String systemMessage;
        private Map<ToolSpecification, ToolExecutor> tools = Map.of();
        private boolean llmInvocationHooks = true;
        private boolean toolInvocationHooks = true;
        private ContentRetriever contentRetriever;
        private RetrievalAugmentor retrievalAugmentor;

        private Builder(ChatModel chatModel) {
            this.baseChatModel = Objects.requireNonNull(chatModel, "chatModel");
            this.chatMemoryProvider = defaultChatMemoryProvider(20);
        }

        /**
         * When {@link SummarizationMiddleware} is present and you do not set {@link #chatMemoryProvider}, {@link #build()}
         * creates an inner {@link MessageWindowChatMemory} with this {@code maxMessages} before wrapping it with
         * {@link SummarizingChatMemory}. Default {@code 100}.
         */
        public Builder summarizationDelegateMaxMessages(int maxMessages) {
            if (maxMessages < 2) {
                throw new IllegalArgumentException("maxMessages must be >= 2");
            }
            this.summarizationDelegateMaxMessages = maxMessages;
            return this;
        }

        /**
         * Append one middleware (chain order: first added = outermost for agent-turn wrap and static {@code ChatModel} stack).
         */
        public Builder middleware(AgentMiddleware middleware) {
            middlewares.add(Objects.requireNonNull(middleware, "middleware"));
            return this;
        }

        /**
         * Append several middleware in one call (same order as chaining {@link #middleware} — first argument is outermost
         * relative to later {@code .middleware(...)} calls).
         */
        public Builder middleware(AgentMiddleware first, AgentMiddleware... rest) {
            Objects.requireNonNull(first, "first");
            middlewares.add(first);
            for (AgentMiddleware m : rest) {
                middlewares.add(Objects.requireNonNull(m, "middleware"));
            }
            return this;
        }

        /** Replace the middleware list. */
        public Builder middlewares(Collection<? extends AgentMiddleware> list) {
            middlewares.clear();
            for (AgentMiddleware m : list) {
                middlewares.add(Objects.requireNonNull(m, "middleware"));
            }
            return this;
        }

        /** Replace the middleware list from a varargs / array (same order as {@link List#of}). */
        public Builder middlewares(AgentMiddleware... entries) {
            middlewares.clear();
            for (AgentMiddleware m : entries) {
                middlewares.add(Objects.requireNonNull(m, "middleware"));
            }
            return this;
        }

        /** Per-{@link ChatModel#doChat} LLM hooks. Default {@code true}. */
        public Builder llmInvocationHooks(boolean enabled) {
            this.llmInvocationHooks = enabled;
            return this;
        }

        /** Per-{@link ToolExecutor#execute} tool hooks. Default {@code true} when tools are non-empty. */
        public Builder toolInvocationHooks(boolean enabled) {
            this.toolInvocationHooks = enabled;
            return this;
        }

        /**
         * Single shared {@link ChatMemory} for all memory ids (typical simple assistant). Mutually exclusive with
         * {@link #chatMemoryProvider}; last call wins.
         */
        public Builder chatMemory(ChatMemory memory) {
            this.chatMemory = Objects.requireNonNull(memory, "memory");
            this.chatMemoryProvider = null;
            this.explicitChatMemoryProvider = false;
            return this;
        }

        /**
         * Per-memory-id store (default: {@link MessageWindowChatMemory}, 20 messages). Used when {@link #chatMemory} is
         * not set. Mutually exclusive with {@link #chatMemory}; last call wins.
         */
        public Builder chatMemoryProvider(ChatMemoryProvider provider) {
            this.chatMemory = null;
            this.chatMemoryProvider = Objects.requireNonNull(provider, "chatMemoryProvider");
            this.explicitChatMemoryProvider = true;
            return this;
        }

        /** Optional system message (constant for all calls). */
        public Builder systemMessage(String message) {
            this.systemMessage = message;
            return this;
        }

        /** Map-based tools (middleware applies to this map). */
        public Builder tools(Map<ToolSpecification, ToolExecutor> toolMap) {
            this.tools = toolMap == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(toolMap));
            return this;
        }

        /** RAG: {@link AiServices#contentRetriever(ContentRetriever)}. */
        public Builder contentRetriever(ContentRetriever retriever) {
            this.contentRetriever = Objects.requireNonNull(retriever, "retriever");
            return this;
        }

        /** RAG: {@link AiServices#retrievalAugmentor(RetrievalAugmentor)}. */
        public Builder retrievalAugmentor(RetrievalAugmentor augmentor) {
            this.retrievalAugmentor = Objects.requireNonNull(augmentor, "augmentor");
            return this;
        }

        public AgentChat build() {
            ArrayList<AgentMiddleware> mw = new ArrayList<>(middlewares);
            SummarizationMiddleware summarization = null;
            int summarizationCount = 0;
            for (AgentMiddleware m : mw) {
                if (m instanceof SummarizationMiddleware sm) {
                    summarizationCount++;
                    if (summarization == null) {
                        summarization = sm;
                    }
                }
            }
            if (summarization != null) {
                if (summarizationCount > 1) {
                    log.warn(
                            "MiddlewareAi: {} SummarizationMiddleware entries in the list; only the first is used for ChatMemory wiring.",
                            summarizationCount);
                }
                mw.removeIf(SummarizationMiddleware.class::isInstance);
                wireSummarizingChatMemory(summarization);
            }

            List<AgentMiddleware> mwFinal = List.copyOf(mw);
            MiddlewareComposition.Options opts = new MiddlewareComposition.Options(llmInvocationHooks, toolInvocationHooks);
            MiddlewareComposition.Result composed = MiddlewareComposition.compose(baseChatModel, tools, mwFinal, opts);
            ChatModel model = composed.chatModel();
            Map<ToolSpecification, ToolExecutor> toolMap = composed.tools();

            AiServices<MiddlewareChatService> aiBuilder = AiServices.builder(MiddlewareChatService.class).chatModel(model);
            if (chatMemory != null) {
                aiBuilder = aiBuilder.chatMemory(chatMemory);
            } else {
                ChatMemoryProvider provider =
                        chatMemoryProvider != null ? chatMemoryProvider : defaultChatMemoryProvider(20);
                aiBuilder = aiBuilder.chatMemoryProvider(provider);
            }
            if (systemMessage != null && !systemMessage.isEmpty()) {
                aiBuilder = aiBuilder.systemMessageProvider(ignored -> systemMessage);
            }
            if (!toolMap.isEmpty()) {
                aiBuilder = aiBuilder.tools(toolMap);
            }
            if (contentRetriever != null) {
                aiBuilder = aiBuilder.contentRetriever(contentRetriever);
            }
            if (retrievalAugmentor != null) {
                aiBuilder = aiBuilder.retrievalAugmentor(retrievalAugmentor);
            }

            MiddlewareChatService inner = aiBuilder.build();
            return mwFinal.isEmpty() ? inner::chat : AgentMiddlewareChain.wrapAgent(inner::chat, mwFinal);
        }

        private void wireSummarizingChatMemory(SummarizationMiddleware sm) {
            if (chatMemory != null) {
                chatMemory = SummarizingChatMemory.fromSummarizationMiddleware(sm, chatMemory);
                return;
            }
            ChatMemoryProvider base;
            if (!explicitChatMemoryProvider) {
                base = defaultChatMemoryProvider(summarizationDelegateMaxMessages);
            } else {
                base = Objects.requireNonNull(chatMemoryProvider, "chatMemoryProvider");
            }
            final ChatMemoryProvider baseFinal = base;
            chatMemoryProvider =
                    memoryId -> SummarizingChatMemory.fromSummarizationMiddleware(sm, baseFinal.get(memoryId));
        }

        private static ChatMemoryProvider defaultChatMemoryProvider(int maxMessages) {
            return memoryId ->
                    MessageWindowChatMemory.builder()
                            .id(memoryId)
                            .maxMessages(maxMessages)
                            .build();
        }
    }
}
