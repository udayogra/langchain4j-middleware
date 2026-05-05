package dev.langchain4j.middleware;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.middleware.modelfallback.ModelFallbackMiddleware;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelFallbackMiddlewareTest {

    @Test
    void primarySuccessNeverTouchesFallback() {
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger fb = new AtomicInteger();
        ChatModel primaryModel =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        primary.incrementAndGet();
                        return ChatResponse.builder().aiMessage(AiMessage.from("primary")).build();
                    }
                };
        ChatModel fallback =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        fb.incrementAndGet();
                        return ChatResponse.builder().aiMessage(AiMessage.from("fb")).build();
                    }
                };
        ModelFallbackMiddleware mw = new ModelFallbackMiddleware(fallback);
        ChatModel hooked =
                MiddlewareComposition.compose(primaryModel, Map.of(), List.of(mw), MiddlewareComposition.Options.defaults())
                        .chatModel();
        ChatResponse r = hooked.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertEquals("primary", r.aiMessage().text());
        assertEquals(1, primary.get());
        assertEquals(0, fb.get());
    }

    @Test
    void firstFallbackUsedWhenPrimaryFails() {
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger fb = new AtomicInteger();
        ChatModel primaryModel =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        primary.incrementAndGet();
                        throw new RuntimeException("primary down");
                    }
                };
        ChatModel fallback =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        fb.incrementAndGet();
                        return ChatResponse.builder().aiMessage(AiMessage.from("from-fallback")).build();
                    }
                };
        ModelFallbackMiddleware mw = new ModelFallbackMiddleware(fallback);
        ChatModel hooked =
                MiddlewareComposition.compose(primaryModel, Map.of(), List.of(mw), MiddlewareComposition.Options.defaults())
                        .chatModel();
        ChatResponse r = hooked.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertEquals("from-fallback", r.aiMessage().text());
        assertEquals(1, primary.get());
        assertEquals(1, fb.get());
    }

    @Test
    void triesSecondFallbackWhenFirstFails() {
        ChatModel primary =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        throw new RuntimeException("p");
                    }
                };
        ChatModel fb1 =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        throw new RuntimeException("fb1");
                    }
                };
        ChatModel fb2 =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("fb2-ok")).build();
                    }
                };
        ModelFallbackMiddleware mw = new ModelFallbackMiddleware(fb1, fb2);
        ChatModel hooked =
                MiddlewareComposition.compose(primary, Map.of(), List.of(mw), MiddlewareComposition.Options.defaults())
                        .chatModel();
        assertEquals("fb2-ok", hooked.chat(ChatRequest.builder().messages(UserMessage.from("x")).build()).aiMessage().text());
    }

    @Test
    void allFailThrowsLastError() {
        ChatModel primary =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        throw new IllegalStateException("primary");
                    }
                };
        ChatModel fb1 =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        throw new IllegalArgumentException("fb1");
                    }
                };
        ModelFallbackMiddleware mw = new ModelFallbackMiddleware(fb1);
        ChatModel hooked =
                MiddlewareComposition.compose(primary, Map.of(), List.of(mw), MiddlewareComposition.Options.defaults())
                        .chatModel();
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> hooked.chat(ChatRequest.builder().messages(UserMessage.from("x")).build()));
        assertEquals("fb1", ex.getMessage());
    }

    @Test
    void emptyFallbackListRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ModelFallbackMiddleware(List.of()));
    }

    @Test
    void listConstructorMatchesOrder() {
        ChatModel primary =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        throw new RuntimeException("p");
                    }
                };
        ChatModel a =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("a")).build();
                    }
                };
        ChatModel b =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("b")).build();
                    }
                };
        ModelFallbackMiddleware mw = new ModelFallbackMiddleware(List.of(a, b));
        ChatModel hooked =
                MiddlewareComposition.compose(primary, Map.of(), List.of(mw), MiddlewareComposition.Options.defaults())
                        .chatModel();
        assertEquals("a", hooked.chat(ChatRequest.builder().messages(UserMessage.from("x")).build()).aiMessage().text());
    }
}
