package dev.langchain4j.middleware.summarization;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.middleware.ChatRequestNormalizer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Shared summarization logic for {@link SummarizationMiddleware} (request rewrite only) and
 * {@link SummarizingChatMemory} (persisted compaction). Returns {@link Optional#empty()} when the message list should
 * stay unchanged.
 */
final class SummarizationCompaction {

    private static final Logger log = LoggerFactory.getLogger(SummarizationCompaction.class);

    private static final int SEARCH_RANGE_FOR_TOOL_PAIRS = 5;

    private SummarizationCompaction() {}

    /**
     * When non-empty, the returned list is the full replacement for {@code original} (including a leading
     * {@link SystemMessage} when one was present).
     */
    static Optional<List<ChatMessage>> maybeCompact(
            List<ChatMessage> original,
            ChatModel summarizerModel,
            SummarizationTrigger trigger,
            int keepMessages,
            String summaryPrompt,
            String summaryPrefix,
            ToLongFunction<List<ChatMessage>> tokenCounter) {
        if (original.isEmpty()) {
            return Optional.empty();
        }
        long approxTokens = tokenCounter.applyAsLong(original);
        if (!trigger.shouldSummarize(approxTokens, original.size())) {
            return Optional.empty();
        }

        Optional<SystemMessage> leadingSystem = Optional.empty();
        List<ChatMessage> conversation = new ArrayList<>(original);
        if (!conversation.isEmpty() && conversation.get(0).type() == ChatMessageType.SYSTEM) {
            leadingSystem = Optional.of((SystemMessage) conversation.remove(0));
        }

        if (conversation.size() <= keepMessages) {
            return Optional.empty();
        }

        int cutoff = findSafeCutoffIndex(conversation, keepMessages);
        if (cutoff <= 0) {
            log.warn(
                    "Summarization: trigger met ({} messages, ~{} est. tokens) but no safe cutoff (cutoff=0); "
                            + "skipping — try raising keepMessages or check tool-call boundaries.",
                    original.size(),
                    approxTokens);
            return Optional.empty();
        }

        List<ChatMessage> toSummarize = new ArrayList<>();
        leadingSystem.ifPresent(toSummarize::add);
        toSummarize.addAll(conversation.subList(0, cutoff));
        List<ChatMessage> preserved = new ArrayList<>(conversation.subList(cutoff, conversation.size()));

        String formatted = formatMessagesForSummary(toSummarize);
        String prompt = summaryPrompt.replace("{messages}", formatted);
        ChatRequest sumReq = ChatRequest.builder().messages(UserMessage.from(prompt)).build();
        sumReq = ChatRequestNormalizer.forDelegate(summarizerModel, sumReq);
        String summaryBody;
        try {
            ChatResponse sumResp = summarizerModel.chat(sumReq);
            summaryBody = sumResp.aiMessage().text().trim();
        } catch (Exception e) {
            log.warn("Summarization: summarizer failed: {}", e.toString());
            summaryBody = "Error generating summary: " + e.getMessage();
        }

        String summaryBlock = summaryPrefix + "\n\n" + summaryBody;
        List<ChatMessage> rewritten = new ArrayList<>();
        leadingSystem.ifPresent(rewritten::add);
        rewritten.add(UserMessage.from(summaryBlock));
        rewritten.addAll(preserved);

        log.info(
                "Summarization: compressed {} messages (~{} est. tokens) to summary + {} preserved",
                original.size(),
                approxTokens,
                preserved.size());
        return Optional.of(rewritten);
    }

    static String formatMessagesForSummary(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m == null) {
                continue;
            }
            switch (m.type()) {
                case SYSTEM -> sb.append("System: ").append(((SystemMessage) m).text()).append('\n');
                case USER -> {
                    UserMessage u = (UserMessage) m;
                    sb.append("User: ")
                            .append(u.hasSingleText() ? u.singleText() : u.toString())
                            .append('\n');
                }
                case AI -> {
                    AiMessage ai = (AiMessage) m;
                    sb.append("AI: ").append(ai.text());
                    if (ai.hasToolExecutionRequests()) {
                        sb.append(" [tool_calls=").append(ai.toolExecutionRequests()).append(']');
                    }
                    sb.append('\n');
                }
                case TOOL_EXECUTION_RESULT -> {
                    ToolExecutionResultMessage tr = (ToolExecutionResultMessage) m;
                    sb.append("Tool(")
                            .append(tr.toolName())
                            .append(", id=")
                            .append(tr.id())
                            .append("): ")
                            .append(tr.text())
                            .append('\n');
                }
                default -> sb.append(m.type()).append(": ").append(m).append('\n');
            }
        }
        return sb.toString();
    }

    static int findSafeCutoffIndex(List<ChatMessage> conversation, int keepMessages) {
        if (conversation.size() <= keepMessages) {
            return 0;
        }
        int rawCutoff = conversation.size() - keepMessages;
        int toolAdjusted = findSafeCutoffPoint(conversation, rawCutoff);
        int start = Math.min(toolAdjusted, rawCutoff);
        for (int i = start; i >= 0; i--) {
            if (isSafeCutoffPoint(conversation, i)) {
                return i;
            }
        }
        return 0;
    }

    static int findSafeCutoffPoint(List<ChatMessage> messages, int cutoffIndex) {
        if (cutoffIndex >= messages.size()) {
            return cutoffIndex;
        }
        if (!(messages.get(cutoffIndex) instanceof ToolExecutionResultMessage)) {
            return cutoffIndex;
        }
        Set<String> toolCallIds = new HashSet<>();
        int idx = cutoffIndex;
        while (idx < messages.size() && messages.get(idx) instanceof ToolExecutionResultMessage tr) {
            if (tr.id() != null) {
                toolCallIds.add(tr.id());
            }
            idx++;
        }
        for (int i = cutoffIndex - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                Set<String> aiIds = toolCallIds(ai);
                for (String id : toolCallIds) {
                    if (aiIds.contains(id)) {
                        return i;
                    }
                }
            }
        }
        return idx;
    }

    static boolean isSafeCutoffPoint(List<ChatMessage> messages, int cutoffIndex) {
        if (cutoffIndex >= messages.size()) {
            return true;
        }
        ChatMessage firstPreserved = messages.get(cutoffIndex);
        if (firstPreserved instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            return false;
        }
        int searchStart = Math.max(0, cutoffIndex - SEARCH_RANGE_FOR_TOOL_PAIRS);
        int searchEnd = Math.min(messages.size(), cutoffIndex + SEARCH_RANGE_FOR_TOOL_PAIRS);
        for (int i = searchStart; i < searchEnd; i++) {
            if (!(messages.get(i) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) {
                continue;
            }
            Set<String> ids = toolCallIds(ai);
            if (cutoffSeparatesToolPair(messages, i, cutoffIndex, ids)) {
                return false;
            }
        }
        return true;
    }

    private static boolean cutoffSeparatesToolPair(
            List<ChatMessage> messages, int aiMessageIndex, int cutoffIndex, Set<String> toolCallIds) {
        for (int j = aiMessageIndex + 1; j < messages.size(); j++) {
            ChatMessage message = messages.get(j);
            if (message instanceof ToolExecutionResultMessage tr
                    && tr.id() != null
                    && toolCallIds.contains(tr.id())) {
                boolean aiBefore = aiMessageIndex < cutoffIndex;
                boolean toolBefore = j < cutoffIndex;
                if (aiBefore != toolBefore) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> toolCallIds(AiMessage ai) {
        Set<String> out = new HashSet<>();
        for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
            if (r.id() != null) {
                out.add(r.id());
            }
        }
        return out;
    }
}
