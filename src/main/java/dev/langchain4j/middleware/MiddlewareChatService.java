package dev.langchain4j.middleware;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * Default {@link dev.langchain4j.service.AiServices} contract used by {@link MiddlewareAi}. Point
 * {@link MiddlewareAi#builder(dev.langchain4j.model.chat.ChatModel)} at this type (or your own interface with the same
 * annotated signature) when wiring manually.
 */
public interface MiddlewareChatService {

    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
