package dev.langchain4j.middleware.demo;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.middleware.AgentChat;
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.summarization.SummarizationMiddleware;
import dev.langchain4j.middleware.summarization.SummarizationTrigger;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Runnable demo: add {@link SummarizationMiddleware} to the middleware list; {@link MiddlewareAi} hoists it into
 * {@link dev.langchain4j.middleware.summarization.SummarizingChatMemory} so compaction is persisted (LangChain-like).
 * Six {@link AgentChat#chat} turns (user + assistant pairs); no API keys — summarizer and primary are stubs.
 *
 * <pre>{@code
 * mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.SummarizationMiddlewareDemo
 * }</pre>
 */
public final class SummarizationMiddlewareDemo {

    public static void main(String[] args) {
        System.out.println("=== SummarizationMiddleware + MiddlewareAi (hoisted memory) demo ===\n");

        ChatModel summarizer =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder()
                                .aiMessage(
                                        AiMessage.from(
                                                "The user sent six numbered lines; earlier content omitted."))
                                .build();
                    }
                };

        ChatModel primary =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        int n = chatRequest.messages().size();
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from("Primary saw " + n + " message(s) after summarization step."))
                                .build();
                    }
                };

        SummarizationMiddleware summarize =
                SummarizationMiddleware.builder(summarizer)
                        .trigger(SummarizationTrigger.messages(8))
                        .keepMessages(2)
                        .build();

        AgentChat client =
                MiddlewareAi.create(primary)
                        .middleware(new LoggingMiddleware("[summarize-demo]", 100), summarize)
                        .build();

        System.out.println(
                "--- Six agent chat turns (trigger at 5 messages, keep 2); primary counts stay low after compaction ---");
        String lastReply = "";
        for (int i = 0; i < 10; i++) {
            lastReply = client.chat("mem", "User line " + i);
            System.out.println("Turn " + i + " reply: " + lastReply);
        }
        System.out.println("\nLast reply: " + lastReply);
        System.out.println("\n=== Done ===");
    }
}
