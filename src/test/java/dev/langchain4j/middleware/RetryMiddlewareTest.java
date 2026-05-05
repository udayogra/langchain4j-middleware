package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.middleware.retry.RetryMiddleware;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryMiddlewareTest {

    @Test
    void llmCallRetriesUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel base =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        int n = calls.incrementAndGet();
                        if (n < 3) {
                            throw new RuntimeException("transient");
                        }
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
        RetryMiddleware retry = new RetryMiddleware(5, 1L, 2.0, 50L, "IllegalArgumentException");
        ChatModel hooked =
                MiddlewareComposition.compose(base, Map.of(), List.of(retry), MiddlewareComposition.Options.defaults())
                        .chatModel();
        ChatResponse r = hooked.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertEquals("ok", r.aiMessage().text());
        assertEquals(3, calls.get());
    }

    @Test
    void llmNonRetryableThrowsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel base =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        calls.incrementAndGet();
                        throw new IllegalArgumentException("bad input");
                    }
                };
        RetryMiddleware retry = new RetryMiddleware(5, 1L, 2.0, 50L, "IllegalArgumentException");
        ChatModel hooked =
                MiddlewareComposition.compose(base, Map.of(), List.of(retry), MiddlewareComposition.Options.defaults())
                        .chatModel();
        assertThrows(IllegalArgumentException.class, () -> hooked.chat(ChatRequest.builder().messages(UserMessage.from("x")).build()));
        assertEquals(1, calls.get());
    }

    @Test
    void toolCallRetriesUntilSuccess() {
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("t")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor base =
                (request, memoryId) -> {
                    int n = calls.incrementAndGet();
                    if (n < 2) {
                        throw new RuntimeException("tool flake");
                    }
                    return "done";
                };
        ChatModel noop =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
                    }
                };
        RetryMiddleware retry = new RetryMiddleware(5, 1L, 2.0, 50L, "IllegalArgumentException");
        Map<ToolSpecification, ToolExecutor> tools =
                MiddlewareComposition.compose(noop, Map.of(spec, base), List.of(retry), MiddlewareComposition.Options.defaults())
                        .tools();
        ToolExecutionRequest treq = ToolExecutionRequest.builder().name("t").arguments("{}").build();
        assertEquals("done", tools.get(spec).execute(treq, "mem"));
        assertEquals(2, calls.get());
    }
}
