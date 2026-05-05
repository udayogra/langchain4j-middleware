package dev.langchain4j.middleware;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

/**
 * Aligns {@link ChatRequest} with vendor {@link ChatModel} expectations before {@link ChatModel#doChat}.
 *
 * <p>Public so other packages (e.g. {@code dev.langchain4j.middleware.modelfallback}) can reuse the same rules when
 * invoking a different {@link ChatModel} than the composed primary.
 *
 * <p>OpenAI: {@link OpenAiChatModel#doChat} casts {@code request.parameters()} to {@link OpenAiChatRequestParameters}.
 * High-level APIs (e.g. AI Services with tools) often attach {@link
 * dev.langchain4j.model.chat.request.DefaultChatRequestParameters}, which causes {@link ClassCastException} when the
 * model is wrapped (same request instance is forwarded).
 *
 * <p>Merge order: start from {@link OpenAiChatModel#defaultRequestParameters()} (includes {@code modelName} from the
 * builder), then {@code overrideWith(request.parameters())}, so per-call params win but the configured model is kept
 * when the request omits it (avoids OpenAI {@code "you must provide a model parameter"}).
 */
public final class ChatRequestNormalizer {

    private ChatRequestNormalizer() {}

    public static ChatRequest forDelegate(ChatModel delegate, ChatRequest request) {
        if (delegate instanceof OpenAiChatModel openAi) {
            if (!(request.parameters() instanceof OpenAiChatRequestParameters)) {
                OpenAiChatRequestParameters merged =
                        openAi.defaultRequestParameters().overrideWith(request.parameters());
                if (request.modelName() != null && !request.modelName().isBlank()) {
                    merged = merged.overrideWith(
                            DefaultChatRequestParameters.builder().modelName(request.modelName()).build());
                }
                return request.toBuilder().parameters(merged).build();
            }
        }
        return request;
    }
}
