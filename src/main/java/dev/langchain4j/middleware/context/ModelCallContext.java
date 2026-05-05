package dev.langchain4j.middleware.context;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.Objects;

/**
 * Context for {@link dev.langchain4j.middleware.AgentMiddleware#beforeLLMCall} and
 * {@link dev.langchain4j.middleware.AgentMiddleware#wrapLLMCall}.
 */
public record ModelCallContext(ChatModel model, ChatRequest request) {
    public ModelCallContext {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(request, "request");
    }
}
