package dev.langchain4j.middleware.demo;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.middleware.AgentChat;
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.maxtoolcalls.MaxToolCallsMiddleware;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable demo: {@link MaxToolCallsMiddleware} caps real tool executions per agent {@code chat} turn; extra tool
 * attempts get a synthetic result string (default {@link MaxToolCallsMiddleware.OnLimit#RETURN_MESSAGE}). Stub model
 * requests the same tool three times in a row so the third invocation hits the cap. No API keys.
 *
 * <pre>{@code
 * mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.MaxToolCallsMiddlewareDemo
 * }</pre>
 */
public final class MaxToolCallsMiddlewareDemo {

    public static void main(String[] args) {
        System.out.println("=== MaxToolCallsMiddleware demo (cap + LoggingMiddleware) ===\n");

        ToolSpecification ping =
                ToolSpecification.builder()
                        .name("ping")
                        .description("Returns pong")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();

        AtomicInteger realToolRuns = new AtomicInteger();
        ToolExecutor pingExecutor =
                (ToolExecutionRequest request, Object memoryId) -> {
                    realToolRuns.incrementAndGet();
                    return "pong";
                };

        AtomicInteger llmRound = new AtomicInteger();
        ChatModel eagerTools =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        int r = llmRound.incrementAndGet();
                        if (r <= 3) {
                            return ChatResponse.builder()
                                    .aiMessage(
                                            AiMessage.from(
                                                    ToolExecutionRequest.builder()
                                                            .id("ping-" + r)
                                                            .name("ping")
                                                            .arguments("{}")
                                                            .build()))
                                    .build();
                        }
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from("Stopped after the tool limit message was returned to the model."))
                                .build();
                    }
                };

        AgentChat client =
                MiddlewareAi.create(eagerTools)
                        .middleware(new LoggingMiddleware("[max-tools-demo]", 100), new MaxToolCallsMiddleware(2))
                        .tools(Map.of(ping, pingExecutor))
                        .systemMessage("Use the ping tool when it helps the user.")
                        .build();

        System.out.println("--- One agent chat: max 2 real ping runs; 3rd is blocked; then a final model message ---");
        String reply = client.chat("mem", "Please exercise tools");
        System.out.println("Assistant reply: " + reply);
        System.out.println("Real ping executions: " + realToolRuns.get());
        System.out.println("Stub LLM rounds: " + llmRound.get());
        System.out.println("\n=== Done ===");
    }
}
