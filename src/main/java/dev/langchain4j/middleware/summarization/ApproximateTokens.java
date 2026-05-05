package dev.langchain4j.middleware.summarization;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * Rough token estimate (~4 chars per token), similar in spirit to LangChain.js approximate counters used by
 * {@code summarizationMiddleware}.
 */
public final class ApproximateTokens {

    private ApproximateTokens() {}

    public static long count(List<ChatMessage> messages) {
        long chars = 0;
        for (ChatMessage m : messages) {
            chars += weight(m);
        }
        return Math.max(1L, (chars + 3) / 4);
    }

    private static int weight(ChatMessage m) {
        if (m == null) {
            return 0;
        }
        ChatMessageType t = m.type();
        if (t == ChatMessageType.SYSTEM) {
            return charLen(((SystemMessage) m).text());
        }
        if (t == ChatMessageType.USER) {
            UserMessage u = (UserMessage) m;
            return u.hasSingleText() ? charLen(u.singleText()) : charLen(u.toString());
        }
        if (t == ChatMessageType.AI) {
            AiMessage ai = (AiMessage) m;
            int n = charLen(ai.text()) + charLen(ai.thinking());
            if (ai.hasToolExecutionRequests()) {
                n += charLen(ai.toolExecutionRequests().toString());
            }
            return Math.max(n, 1);
        }
        if (t == ChatMessageType.TOOL_EXECUTION_RESULT) {
            ToolExecutionResultMessage tr = (ToolExecutionResultMessage) m;
            return charLen(tr.toolName()) + charLen(tr.text()) + charLen(tr.id());
        }
        return charLen(m.toString());
    }

    private static int charLen(String s) {
        return s == null ? 0 : s.length();
    }
}
