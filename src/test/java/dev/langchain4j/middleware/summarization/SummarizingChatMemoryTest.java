package dev.langchain4j.middleware.summarization;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizingChatMemoryTest {

    @Test
    void messages_persistsCompactionSoCountDrops() {
        AtomicInteger summarizeCalls = new AtomicInteger();
        ChatModel summarizer =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        summarizeCalls.incrementAndGet();
                        return ChatResponse.builder().aiMessage(AiMessage.from("COMPACT")).build();
                    }
                };

        MessageWindowChatMemory inner =
                MessageWindowChatMemory.builder().id("t").maxMessages(100).build();
        SummarizingChatMemory memory =
                SummarizingChatMemory.builder(summarizer)
                        .delegate(inner)
                        .trigger(SummarizationTrigger.messages(5))
                        .keepMessages(2)
                        .build();

        for (int i = 0; i < 6; i++) {
            memory.add(UserMessage.from("line-" + i));
        }

        List<ChatMessage> firstRead = memory.messages();
        assertEquals(3, firstRead.size());
        assertTrue(((UserMessage) firstRead.get(0)).singleText().contains("COMPACT"));
        assertEquals("line-4", ((UserMessage) firstRead.get(1)).singleText());
        assertEquals("line-5", ((UserMessage) firstRead.get(2)).singleText());
        assertEquals(1, summarizeCalls.get());

        List<ChatMessage> secondRead = memory.messages();
        assertEquals(3, secondRead.size());
        assertEquals(1, summarizeCalls.get(), "trigger should stay false after persisted shrink");
    }

    @Test
    void messages_secondGrowthCycleSummarizesAgain() {
        AtomicInteger summarizeCalls = new AtomicInteger();
        ChatModel summarizer =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        summarizeCalls.incrementAndGet();
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from("S" + summarizeCalls.get()))
                                .build();
                    }
                };

        MessageWindowChatMemory inner =
                MessageWindowChatMemory.builder().id("t").maxMessages(100).build();
        SummarizingChatMemory memory =
                SummarizingChatMemory.builder(summarizer)
                        .delegate(inner)
                        .trigger(SummarizationTrigger.messages(5))
                        .keepMessages(2)
                        .build();

        List<ChatMessage> batch = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            batch.add(UserMessage.from("a" + i));
        }
        memory.add(batch);

        memory.messages();
        assertEquals(1, summarizeCalls.get());

        for (int i = 0; i < 5; i++) {
            memory.add(UserMessage.from("b" + i));
        }
        memory.messages();
        assertEquals(2, summarizeCalls.get());
    }
}
