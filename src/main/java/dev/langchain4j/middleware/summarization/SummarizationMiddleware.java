package dev.langchain4j.middleware.summarization;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.middleware.AgentMiddleware;
import dev.langchain4j.middleware.ChatRequestNormalizer;
import dev.langchain4j.middleware.context.ModelCallContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;

/**
 * Phase-1 conversation summarization aligned with LangChain.js
 * <a href="https://github.com/langchain-ai/langchainjs/blob/main/libs/langchain/src/agents/middleware/summarization.ts">{@code summarizationMiddleware}</a>:
 * when {@link SummarizationTrigger} conditions hold, older messages (except a trailing window of {@code keepMessages})
 * are summarized with a separate {@link ChatModel}, then replaced by one {@link UserMessage} carrying the summary
 * plus the preserved tail. Tool-call / tool-result boundaries in the preserved region are kept consistent.
 *
 * <p><strong>{@link dev.langchain4j.middleware.MiddlewareAi}:</strong> instances in the middleware list are
 * <strong>not</strong> run as {@link AgentMiddleware#wrapLLMCall}; they are removed from the LLM chain and used only
 * as configuration for {@link SummarizingChatMemory} wrapping your {@link dev.langchain4j.memory.ChatMemory} /
 * {@link dev.langchain4j.memory.chat.ChatMemoryProvider}, so compaction is persisted (LangChain-like). Tune the inner
 * window with {@link dev.langchain4j.middleware.MiddlewareAi.Builder#summarizationDelegateMaxMessages(int)} when you
 * rely on the default provider.
 *
 * <p><strong>Outside {@code MiddlewareAi}</strong> (e.g. raw {@link dev.langchain4j.middleware.MiddlewareComposition}):
 * this class only rewrites the in-flight {@link ModelCallContext#request()}; it does not update memory unless you also
 * use {@link SummarizingChatMemory}.
 *
 * <p>Use a dedicated small/cheap {@link ChatModel} for {@link Builder#summarizerModel}.
 *
 * <p>If {@code cutoff == 0} (no safe split), the request is left unchanged and the primary will keep seeing the full
 * growing memory — a WARN is logged in that case.
 */
public final class SummarizationMiddleware implements AgentMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SummarizationMiddleware.class);

    /** @see Builder#summaryPrompt(String) */
    public static final String DEFAULT_SUMMARY_PROMPT =
            "Summarize the following conversation for context continuity. Respond ONLY with the summary text, "
                    + "no preamble or markdown fences.\n\n<messages>\n{messages}\n</messages>";

    /** @see Builder#summaryPrefix(String) */
    public static final String DEFAULT_SUMMARY_PREFIX = "Here is a summary of the conversation to date:";

    private final ChatModel summarizerModel;
    private final SummarizationTrigger trigger;
    private final int keepMessages;
    private final String summaryPrompt;
    private final String summaryPrefix;
    private final ToLongFunction<List<ChatMessage>> tokenCounter;

    private SummarizationMiddleware(Builder b) {
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

    /** Exposed for {@link dev.langchain4j.middleware.MiddlewareAi} memory wiring (same model as {@link Builder}). */
    public ChatModel summarizerModel() {
        return summarizerModel;
    }

    /** @see Builder#trigger */
    public SummarizationTrigger trigger() {
        return trigger;
    }

    /** @see Builder#keepMessages */
    public int keepMessages() {
        return keepMessages;
    }

    /** @see Builder#summaryPrompt */
    public String summaryPrompt() {
        return summaryPrompt;
    }

    /** @see Builder#summaryPrefix */
    public String summaryPrefix() {
        return summaryPrefix;
    }

    /** @see Builder#tokenCounter */
    public ToLongFunction<List<ChatMessage>> tokenCounter() {
        return tokenCounter;
    }

    /** For tests and tooling; delegates to shared compaction helpers. */
    static int findSafeCutoffIndex(List<ChatMessage> conversation, int keepMessages) {
        return SummarizationCompaction.findSafeCutoffIndex(conversation, keepMessages);
    }

    /** For tests and tooling. */
    static String formatMessagesForSummary(List<ChatMessage> messages) {
        return SummarizationCompaction.formatMessagesForSummary(messages);
    }

    @Override
    public ChatResponse wrapLLMCall(ModelCallContext context, LlmCallChain handler) {
        List<ChatMessage> original = context.request().messages();
        Optional<List<ChatMessage>> rewritten =
                SummarizationCompaction.maybeCompact(
                        original,
                        summarizerModel,
                        trigger,
                        keepMessages,
                        summaryPrompt,
                        summaryPrefix,
                        tokenCounter);
        if (rewritten.isEmpty()) {
            return handler.proceed(context);
        }
        ChatRequest newReq = context.request().toBuilder().messages(rewritten.get()).build();
        ChatRequest effective = ChatRequestNormalizer.forDelegate(context.model(), newReq);
        log.debug("SummarizationMiddleware: applying in-flight rewrite (use MiddlewareAi for persisted summarization)");
        return handler.proceed(new ModelCallContext(context.model(), effective));
    }

    public static final class Builder {

        private final ChatModel summarizerModel;
        private SummarizationTrigger trigger;
        private int keepMessages = 20;
        private String summaryPrompt = DEFAULT_SUMMARY_PROMPT;
        private String summaryPrefix = DEFAULT_SUMMARY_PREFIX;
        private ToLongFunction<List<ChatMessage>> tokenCounter = ApproximateTokens::count;

        private Builder(ChatModel summarizerModel) {
            this.summarizerModel = Objects.requireNonNull(summarizerModel, "summarizerModel");
        }

        /** Required: when to summarize (token and/or message thresholds). */
        public Builder trigger(SummarizationTrigger trigger) {
            this.trigger = Objects.requireNonNull(trigger, "trigger");
            return this;
        }

        /**
         * How many trailing {@link ChatMessage}s to keep verbatim after summarization (phase 1: message-count only,
         * matching {@code keep: { messages: K }} in LangChain.js).
         */
        public Builder keepMessages(int keepMessages) {
            this.keepMessages = keepMessages;
            return this;
        }

        /** Prompt template; must contain {@code {messages}} placeholder. */
        public Builder summaryPrompt(String summaryPrompt) {
            this.summaryPrompt = Objects.requireNonNull(summaryPrompt, "summaryPrompt");
            if (!summaryPrompt.contains("{messages}")) {
                throw new IllegalArgumentException("summaryPrompt must contain '{messages}' placeholder");
            }
            return this;
        }

        /**
         * Text prepended before the summarizer’s reply in the injected {@link dev.langchain4j.data.message.UserMessage}
         * ({@code prefix + "\n\n" + summarizerBody}). Use an empty string if the model already supplies a full lead-in;
         * otherwise ask the summarizer (via {@link #summaryPrompt}) to return plain prose only (no second headline like
         * {@code "Summary:"}), or you will get redundant framing next to the default prefix.
         */
        public Builder summaryPrefix(String summaryPrefix) {
            this.summaryPrefix = Objects.requireNonNull(summaryPrefix, "summaryPrefix");
            return this;
        }

        /** Defaults to {@link ApproximateTokens#count}. */
        public Builder tokenCounter(ToLongFunction<List<ChatMessage>> tokenCounter) {
            this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
            return this;
        }

        public SummarizationMiddleware build() {
            if (trigger == null) {
                throw new IllegalStateException("trigger is required");
            }
            return new SummarizationMiddleware(this);
        }
    }
}
