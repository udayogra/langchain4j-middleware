package dev.langchain4j.middleware.context;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Objects;

/** Context for {@link dev.langchain4j.middleware.AgentMiddleware#afterLLMCall}. */
public record ModelCallAfterContext(ChatModel model, ChatRequest request, ChatResponse response) {
    public ModelCallAfterContext {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
    }
}
