package dev.langchain4j.middleware.demo;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.middleware.AgentChat;
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.modelfallback.ModelFallbackMiddleware;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable demo: {@link ModelFallbackMiddleware} with stub {@link ChatModel}s — primary fails, first fallback fails,
 * second succeeds. No API keys.
 *
 * <pre>{@code
 * mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.ModelFallbackMiddlewareDemo
 * }</pre>
 */
public final class ModelFallbackMiddlewareDemo {

    public static void main(String[] args) {
        System.out.println("=== ModelFallbackMiddleware demo (stubs + LoggingMiddleware) ===\n");

        AtomicInteger primaryRuns = new AtomicInteger();
        ChatModel flakyPrimary =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        primaryRuns.incrementAndGet();
                        throw new RuntimeException("simulated primary outage");
                    }
                };

        AtomicInteger fb1Runs = new AtomicInteger();
        ChatModel flakyFallback1 =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        fb1Runs.incrementAndGet();
                        throw new RuntimeException("simulated fallback-1 outage");
                    }
                };

        AtomicInteger fb2Runs = new AtomicInteger();
        ChatModel healthyFallback2 =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        fb2Runs.incrementAndGet();
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from("Reply from second fallback (primary and first fallback failed)."))
                                .build();
                    }
                };

        ModelFallbackMiddleware fallback = new ModelFallbackMiddleware(flakyFallback1, healthyFallback2);
        LoggingMiddleware logging = new LoggingMiddleware("[model-fallback-demo]", 100);

        AgentChat client = MiddlewareAi.create(flakyPrimary).middleware(logging, fallback).build();

        System.out.println("--- Agent chat: expect primary + fallback1 failures logged, answer from fallback2 ---");
        String reply = client.chat("mem", "hello");
        System.out.println("Assistant reply: " + reply);
        System.out.println("Primary doChat invocations: " + primaryRuns.get());
        System.out.println("Fallback #1 doChat invocations: " + fb1Runs.get());
        System.out.println("Fallback #2 doChat invocations: " + fb2Runs.get());
        System.out.println("\n=== Done ===");
    }
}
