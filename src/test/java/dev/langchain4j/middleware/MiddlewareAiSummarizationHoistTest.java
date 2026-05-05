package dev.langchain4j.middleware;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.middleware.summarization.SummarizationMiddleware;
import dev.langchain4j.middleware.summarization.SummarizationTrigger;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiddlewareAiSummarizationHoistTest {

    @Test
    void summarizationMiddleware_isHoistedIntoChatMemory_notLlmChain() {
        AtomicInteger summarizeCalls = new AtomicInteger();
        AtomicReference<Integer> primarySizes = new AtomicReference<>();

        ChatModel summarizer =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        summarizeCalls.incrementAndGet();
                        return ChatResponse.builder().aiMessage(AiMessage.from("COMPACT")).build();
                    }
                };

        ChatModel primary =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        primarySizes.set(chatRequest.messages().size());
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };

        SummarizationMiddleware summarize =
                SummarizationMiddleware.builder(summarizer)
                        .trigger(SummarizationTrigger.messages(5))
                        .keepMessages(2)
                        .build();

        AgentChat client = MiddlewareAi.create(primary).middleware(summarize).build();

        for (int i = 0; i < 6; i++) {
            client.chat("t", "line-" + i);
        }

        assertTrue(summarizeCalls.get() >= 1, "expected persisted summarization to run at least once");
        assertEquals(3, primarySizes.get().intValue(), "last primary call should see compacted thread size");
    }
}
