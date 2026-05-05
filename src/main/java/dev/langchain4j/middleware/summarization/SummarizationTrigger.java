package dev.langchain4j.middleware.summarization;

import java.util.Objects;

/**
 * When to run summarization (LangChain.js {@code summarization} middleware {@code trigger} semantics for phase 1).
 *
 * <p>If both {@link #minTokens()} and {@link #minMessages()} are set, <strong>both</strong> must be satisfied (AND). If
 * only one is set, that condition alone gates summarization.
 */
public record SummarizationTrigger(Integer minTokens, Integer minMessages) {

    public SummarizationTrigger {
        boolean hasTokens = minTokens != null;
        boolean hasMessages = minMessages != null;
        if (!hasTokens && !hasMessages) {
            throw new IllegalArgumentException("At least one of minTokens or minMessages must be set");
        }
        if (hasTokens && minTokens <= 0) {
            throw new IllegalArgumentException("minTokens must be > 0 when set");
        }
        if (hasMessages && minMessages <= 0) {
            throw new IllegalArgumentException("minMessages must be > 0 when set");
        }
    }

    /** Fire when approximate token count is at least {@code minTokens}. */
    public static SummarizationTrigger tokens(int minTokens) {
        return new SummarizationTrigger(minTokens, null);
    }

    /** Fire when message count is at least {@code minMessages}. */
    public static SummarizationTrigger messages(int minMessages) {
        return new SummarizationTrigger(null, minMessages);
    }

    /** Fire when both approximate tokens and message count reach the given thresholds. */
    public static SummarizationTrigger tokensAndMessages(int minTokens, int minMessages) {
        return new SummarizationTrigger(minTokens, minMessages);
    }

    /**
     * @param approxTotalTokens approximate token count for the full message list (including any leading system message)
     * @param totalMessageCount number of messages in the list passed to the model
     */
    public boolean shouldSummarize(long approxTotalTokens, int totalMessageCount) {
        boolean tokensOk = minTokens == null || approxTotalTokens >= minTokens;
        boolean messagesOk = minMessages == null || totalMessageCount >= minMessages;
        return tokensOk && messagesOk;
    }

    @Override
    public String toString() {
        return "SummarizationTrigger[minTokens="
                + Objects.toString(minTokens, "-")
                + ", minMessages="
                + Objects.toString(minMessages, "-")
                + "]";
    }
}
