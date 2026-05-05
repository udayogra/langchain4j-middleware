package dev.langchain4j.middleware;

/**
 * One agent turn: memory-scoped id plus user text in, assistant text out. Matches the common
 * {@link dev.langchain4j.service.AiServices} pattern {@code String chat(@MemoryId String memoryId, @UserMessage String userMessage)}
 * so you can pass {@code aiService::chat} to {@link MiddlewareChatClient#wrap(AgentChat, java.util.List)}.
 */
@FunctionalInterface
public interface AgentChat {

    String chat(String memoryId, String userMessage);
}
