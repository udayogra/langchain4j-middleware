package dev.langchain4j.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

class ChatRequestNormalizerTest {

    @Test
    void openAiDelegate_mergesDefaultParametersIntoOpenAiType() {
        OpenAiChatModel model =
                OpenAiChatModel.builder().apiKey("test-key-not-used").modelName("gpt-4o-mini").build();

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .parameters(DefaultChatRequestParameters.builder().temperature(0.1).build())
                .build();

        assertInstanceOf(DefaultChatRequestParameters.class, request.parameters());

        ChatRequest normalized = ChatRequestNormalizer.forDelegate(model, request);

        assertInstanceOf(OpenAiChatRequestParameters.class, normalized.parameters());
        assertEquals(0.1, normalized.temperature());
        assertEquals("gpt-4o-mini", normalized.modelName());
    }

    @Test
    void openAiDelegate_keepsConfiguredModelWhenRequestParametersOmitModelName() {
        OpenAiChatModel model =
                OpenAiChatModel.builder().apiKey("test-key-not-used").modelName("gpt-4o-mini").build();

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("hi"))
                .parameters(DefaultChatRequestParameters.EMPTY)
                .build();

        ChatRequest normalized = ChatRequestNormalizer.forDelegate(model, request);

        assertEquals("gpt-4o-mini", normalized.modelName());
        assertEquals("gpt-4o-mini", normalized.parameters().modelName());
    }

    @Test
    void openAiDelegate_leavesRequestUnchangedWhenAlreadyOpenAiParameters() {
        OpenAiChatModel model =
                OpenAiChatModel.builder().apiKey("test-key-not-used").modelName("gpt-4o-mini").build();

        OpenAiChatRequestParameters params =
                OpenAiChatRequestParameters.builder().temperature(0.2).build();
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("x")).parameters(params).build();

        assertSame(request, ChatRequestNormalizer.forDelegate(model, request));
    }
}
