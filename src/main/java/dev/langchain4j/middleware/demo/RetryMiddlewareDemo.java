package dev.langchain4j.middleware.demo;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.middleware.AgentChat;
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.retry.RetryMiddleware;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable demo: {@link RetryMiddleware} on LLM and tool hooks with a stub model and stub tool that fail a few times,
 * then succeed. No API keys; uses short delays so the run finishes quickly.
 *
 * <pre>{@code
 * mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.RetryMiddlewareDemo
 * }</pre>
 */
public final class RetryMiddlewareDemo {

    public static void main(String[] args) {
        System.out.println("=== Retry middleware demo (RetryMiddleware + LoggingMiddleware) ===\n");

        RetryMiddleware retry =
                new RetryMiddleware(
                        /* maxRetries */ 5,
                        /* initialDelayMillis */ 25L,
                        /* backoffMultiplier */ 2.0,
                        /* maxDelayMillis */ 200L,
                        /* nonRetryable */ "IllegalArgumentException");
        LoggingMiddleware logging = new LoggingMiddleware("[retry-demo]", 80);

        ToolSpecification ping =
                ToolSpecification.builder()
                        .name("ping")
                        .description("Returns pong")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();

        // --- Part 1: LLM retries only (no tools) ---
        AtomicInteger llmCalls = new AtomicInteger();
        ChatModel flakyLlm =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        int n = llmCalls.incrementAndGet();
                        if (n < 3) {
                            System.out.println("[stub LLM] attempt " + n + " → simulated failure");
                            throw new RuntimeException("simulated LLM outage");
                        }
                        System.out.println("[stub LLM] attempt " + n + " → success");
                        return ChatResponse.builder().aiMessage(AiMessage.from("Recovered after retries.")).build();
                    }
                };

        AgentChat llmOnlyClient = MiddlewareAi.create(flakyLlm).middleware(logging, retry).build();

        System.out.println("--- Agent chat, LLM-only (expect 2 failures, then success) ---");
        String llmReply = llmOnlyClient.chat("mem", "hello");
        System.out.println("LLM reply: " + llmReply);
        System.out.println("Stub LLM doChat invocations: " + llmCalls.get());
        System.out.println();

        // --- Part 2: tool retries inside one agent turn (model requests ping; tool flakes then succeeds) ---
        AtomicInteger modelTurns = new AtomicInteger();
        ChatModel toolFlowLlm =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        int turn = modelTurns.incrementAndGet();
                        if (turn == 1) {
                            return ChatResponse.builder()
                                    .aiMessage(
                                            AiMessage.from(
                                                    ToolExecutionRequest.builder()
                                                            .id("t1")
                                                            .name("ping")
                                                            .arguments("{}")
                                                            .build()))
                                    .build();
                        }
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from("Done after tool retries."))
                                .build();
                    }
                };

        AtomicInteger toolCalls = new AtomicInteger();
        ToolExecutor flakyTool =
                (ToolExecutionRequest request, Object memoryId) -> {
                    int n = toolCalls.incrementAndGet();
                    if (n < 2) {
                        System.out.println("[stub tool] attempt " + n + " → simulated failure");
                        throw new RuntimeException("simulated tool flake");
                    }
                    System.out.println("[stub tool] attempt " + n + " → pong");
                    return "pong";
                };

        AgentChat toolClient =
                MiddlewareAi.create(toolFlowLlm)
                        .middleware(logging, retry)
                        .tools(Map.of(ping, flakyTool))
                        .systemMessage(
                                "When the user says trigger, you MUST call the ping tool once, then answer using its result.")
                        .build();

        System.out.println("--- Agent chat with tools (expect 1 tool failure, then success) ---");
        String toolPathReply = toolClient.chat("mem2", "trigger");
        System.out.println("Final reply: " + toolPathReply);
        System.out.println("Stub tool execute invocations: " + toolCalls.get());
        System.out.println("\n=== Done ===");
    }
}
