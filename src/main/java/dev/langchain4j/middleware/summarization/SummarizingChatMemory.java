package dev.langchain4j.middleware.summarization;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;

/**
 * {@link ChatMemory} decorator that applies the same compaction rules as {@link SummarizationMiddleware}, but
 * <strong>persists</strong> the result into the delegate memory on each {@link #messages()} read. After a successful
 * compaction, message count drops (summary user + preserved tail), so {@link SummarizationTrigger} thresholds are
 * not permanently satisfied — matching LangChain.js summarization middleware behavior.
 *
 * <p>With {@link dev.langchain4j.middleware.MiddlewareAi}, add {@link SummarizationMiddleware} to the middleware list;
 * {@code MiddlewareAi} wires this type automatically. For {@link dev.langchain4j.middleware.MiddlewareComposition} or
 * custom {@link dev.langchain4j.service.AiServices} setup, call {@link #fromSummarizationMiddleware} or {@link #builder}
 * yourself. Use a generous inner {@code maxMessages} on the delegate so the full thread is available until compaction.
 */
public final class SummarizingChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final ChatModel summarizerModel;
    private final SummarizationTrigger trigger;
    private final int keepMessages;
    private final String summaryPrompt;
    private final String summaryPrefix;
    private final ToLongFunction<List<ChatMessage>> tokenCounter;

    private SummarizingChatMemory(Builder b) {
        this.delegate = Objects.requireNonNull(b.delegate, "delegate");
        this.summarizerModel = Objects.requireNonNull(b.summarizerModel, "summarizerModel");
        this.trigger = Objects.requireNonNull(b.trigger, "trigger");
        this.keepMessages = b.keepMessages;
        this.summaryPrompt = Objects.requireNonNull(b.summaryPrompt, "summaryPrompt");
        this.summaryPrefix = Objects.requireNonNull(b.summaryPrefix, "summaryPrefix");
        this.tokenCounter = Objects.requireNonNull(b.tokenCounter, "tokenCounter");
        if (keepMessages < 1) {
            throw new IllegalArgumentException("keepMessages must be >= 1");
        }
    }

    public static Builder builder(ChatModel summarizerModel) {
        return new Builder(summarizerModel);
    }

    /**
     * Builds memory using the same summarizer, trigger, prompts, and token counter as {@code sm} (typically built via
     * {@link SummarizationMiddleware#builder}).
     */
    public static SummarizingChatMemory fromSummarizationMiddleware(SummarizationMiddleware sm, ChatMemory delegate) {
        return builder(sm.summarizerModel())
                .delegate(delegate)
                .trigger(sm.trigger())
                .keepMessages(sm.keepMessages())
                .summaryPrompt(sm.summaryPrompt())
                .summaryPrefix(sm.summaryPrefix())
                .tokenCounter(sm.tokenCounter())
                .build();
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(message);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> snapshot = new ArrayList<>(delegate.messages());
        Optional<List<ChatMessage>> rewritten =
                SummarizationCompaction.maybeCompact(
                        snapshot,
                        summarizerModel,
                        trigger,
                        keepMessages,
                        summaryPrompt,
                        summaryPrefix,
                        tokenCounter);
        if (rewritten.isEmpty()) {
            return List.copyOf(snapshot);
        }
        delegate.clear();
        for (ChatMessage m : rewritten.get()) {
            delegate.add(m);
        }
        return List.copyOf(delegate.messages());
    }

    public static final class Builder {

        private final ChatModel summarizerModel;
        private ChatMemory delegate;
        private SummarizationTrigger trigger;
        private int keepMessages = 20;
        private String summaryPrompt = SummarizationMiddleware.DEFAULT_SUMMARY_PROMPT;
        private String summaryPrefix = SummarizationMiddleware.DEFAULT_SUMMARY_PREFIX;
        private ToLongFunction<List<ChatMessage>> tokenCounter = ApproximateTokens::count;

        private Builder(ChatModel summarizerModel) {
            this.summarizerModel = Objects.requireNonNull(summarizerModel, "summarizerModel");
        }

        /** Underlying store (e.g. {@link dev.langchain4j.memory.chat.MessageWindowChatMemory}). */
        public Builder delegate(ChatMemory delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            return this;
        }

        public Builder trigger(SummarizationTrigger trigger) {
            this.trigger = Objects.requireNonNull(trigger, "trigger");
            return this;
        }

        public Builder keepMessages(int keepMessages) {
            this.keepMessages = keepMessages;
            return this;
        }

        public Builder summaryPrompt(String summaryPrompt) {
            this.summaryPrompt = Objects.requireNonNull(summaryPrompt, "summaryPrompt");
            if (!summaryPrompt.contains("{messages}")) {
                throw new IllegalArgumentException("summaryPrompt must contain '{messages}' placeholder");
            }
            return this;
        }

        /** @see SummarizationMiddleware.Builder#summaryPrefix(String) */
        public Builder summaryPrefix(String summaryPrefix) {
            this.summaryPrefix = Objects.requireNonNull(summaryPrefix, "summaryPrefix");
            return this;
        }

        public Builder tokenCounter(ToLongFunction<List<ChatMessage>> tokenCounter) {
            this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
            return this;
        }

        public SummarizingChatMemory build() {
            if (delegate == null) {
                throw new IllegalStateException("delegate is required");
            }
            if (trigger == null) {
                throw new IllegalStateException("trigger is required");
            }
            return new SummarizingChatMemory(this);
        }
    }
}
