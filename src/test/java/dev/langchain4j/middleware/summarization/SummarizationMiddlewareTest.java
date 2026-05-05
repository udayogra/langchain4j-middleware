package dev.langchain4j.middleware.summarization;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.middleware.MiddlewareComposition;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizationMiddlewareTest {

    @Test
    void findSafeCutoff_neverStartsPreservedWithIncompleteToolAi() {
        ToolExecutionRequest treq =
                ToolExecutionRequest.builder().id("t1").name("x").arguments("{}").build();
        List<ChatMessage> conv = new ArrayList<>();
        conv.add(UserMessage.from("u0"));
        conv.add(AiMessage.from(treq));
        conv.add(ToolExecutionResultMessage.from(treq, "result"));
        conv.add(UserMessage.from("u1"));
        conv.add(UserMessage.from("u2"));
        // keep last 3 -> raw cutoff 2 would start preserved at Tool; walk back to safe point (0).
        int cutoff = SummarizationMiddleware.findSafeCutoffIndex(conv, 3);
        assertEquals(0, cutoff);
    }

    @Test
    void findSafeCutoff_plainUsers() {
        List<ChatMessage> conv = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            conv.add(UserMessage.from("m" + i));
        }
        assertEquals(4, SummarizationMiddleware.findSafeCutoffIndex(conv, 2));
    }

    @Test
    void summarizationRewritesMessagesBeforePrimary() {
        AtomicReference<List<ChatMessage>> seen = new AtomicReference<>();
        ChatModel primary =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        seen.set(chatRequest.messages());
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
        ChatModel summarizer =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("COMPACT")).build();
                    }
                };
        SummarizationMiddleware mw =
                SummarizationMiddleware.builder(summarizer)
                        .trigger(SummarizationTrigger.messages(5))
                        .keepMessages(2)
                        .build();
        ChatModel hooked =
                MiddlewareComposition.compose(primary, Map.of(), List.of(mw), MiddlewareComposition.Options.defaults())
                        .chatModel();

        List<ChatMessage> msgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            msgs.add(UserMessage.from("line-" + i));
        }
        hooked.chat(ChatRequest.builder().messages(msgs).build());

        List<ChatMessage> out = seen.get();
        assertEquals(3, out.size());
        assertTrue(out.get(0) instanceof UserMessage);
        assertTrue(((UserMessage) out.get(0)).singleText().contains("COMPACT"));
        assertEquals("line-4", ((UserMessage) out.get(1)).singleText());
        assertEquals("line-5", ((UserMessage) out.get(2)).singleText());
    }

    @Test
    void leadingSystemRestoredWhenNoSummarizationNeeded() {
        AtomicReference<Integer> size = new AtomicReference<>();
        ChatModel primary =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        size.set(chatRequest.messages().size());
                        return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
                    }
                };
        ChatModel summarizer =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("nope")).build();
                    }
                };
        SummarizationMiddleware mw =
                SummarizationMiddleware.builder(summarizer)
                        .trigger(SummarizationTrigger.messages(100))
                        .keepMessages(2)
                        .build();
        ChatModel hooked =
                MiddlewareComposition.compose(primary, Map.of(), List.of(mw), MiddlewareComposition.Options.defaults())
                        .chatModel();
        List<ChatMessage> msgs = List.of(SystemMessage.from("sys"), UserMessage.from("hi"));
        hooked.chat(ChatRequest.builder().messages(msgs).build());
        assertEquals(2, size.get());
    }

    @Test
    void formatMessages_containsRoles() {
        ToolExecutionRequest treq =
                ToolExecutionRequest.builder().id("1").name("ping").arguments("{}").build();
        List<ChatMessage> list =
                List.of(
                        SystemMessage.from("s"),
                        UserMessage.from("u"),
                        AiMessage.from("thinking", List.of(treq)),
                        ToolExecutionResultMessage.from(treq, "pong"));
        String f = SummarizationMiddleware.formatMessagesForSummary(list);
        assertTrue(f.contains("System:"));
        assertTrue(f.contains("User:"));
        assertTrue(f.contains("AI:"));
        assertTrue(f.contains("Tool(ping"));
    }
}
