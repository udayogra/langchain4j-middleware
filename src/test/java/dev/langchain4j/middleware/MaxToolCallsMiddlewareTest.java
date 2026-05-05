package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.middleware.maxtoolcalls.MaxToolCallsExceededException;
import dev.langchain4j.middleware.maxtoolcalls.MaxToolCallsMiddleware;
import dev.langchain4j.middleware.maxtoolcalls.MaxToolCallsMiddleware.OnLimit;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxToolCallsMiddlewareTest {

    @Test
    void allowsUpToMaxThenReturnsSyntheticMessage() {
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("ping")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        AtomicInteger baseRuns = new AtomicInteger();
        ToolExecutor base = (req, mem) -> {
            baseRuns.incrementAndGet();
            return "pong";
        };
        ChatModel noop =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
                    }
                };
        MaxToolCallsMiddleware limit = new MaxToolCallsMiddleware(2);
        Map<ToolSpecification, ToolExecutor> tools =
                MiddlewareComposition.compose(
                                noop,
                                Map.of(spec, base),
                                List.of(limit),
                                MiddlewareComposition.Options.defaults())
                        .tools();
        ToolExecutionRequest treq = ToolExecutionRequest.builder().name("ping").arguments("{}").build();

        limit.beforeAgentRun(new AgentRunContext("m", "u"));
        assertEquals("pong", tools.get(spec).execute(treq, "m"));
        assertEquals("pong", tools.get(spec).execute(treq, "m"));
        String blocked = tools.get(spec).execute(treq, "m");
        assertTrue(blocked.contains("tool call limit"));
        assertTrue(blocked.contains("ping"));
        assertEquals(2, baseRuns.get());
    }

    @Test
    void throwOnLimitSkipsDelegate() {
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("t")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        AtomicInteger baseRuns = new AtomicInteger();
        ToolExecutor base = (req, mem) -> {
            baseRuns.incrementAndGet();
            return "ok";
        };
        ChatModel noop =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
                    }
                };
        MaxToolCallsMiddleware limit = new MaxToolCallsMiddleware(1, OnLimit.THROW);
        Map<ToolSpecification, ToolExecutor> tools =
                MiddlewareComposition.compose(
                                noop,
                                Map.of(spec, base),
                                List.of(limit),
                                MiddlewareComposition.Options.defaults())
                        .tools();
        ToolExecutionRequest treq = ToolExecutionRequest.builder().name("t").arguments("{}").build();

        limit.beforeAgentRun(new AgentRunContext("m", "u"));
        assertEquals("ok", tools.get(spec).execute(treq, "m"));
        MaxToolCallsExceededException ex =
                assertThrows(MaxToolCallsExceededException.class, () -> tools.get(spec).execute(treq, "m"));
        assertEquals(1, ex.maxCalls());
        assertEquals(2, ex.callNumber());
        assertEquals("t", ex.toolName());
        assertEquals(1, baseRuns.get());
    }

    @Test
    void beforeAgentRunResetsCounter() {
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("t")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        AtomicInteger baseRuns = new AtomicInteger();
        ToolExecutor base = (req, mem) -> {
            baseRuns.incrementAndGet();
            return "ok";
        };
        ChatModel noop =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
                    }
                };
        MaxToolCallsMiddleware limit = new MaxToolCallsMiddleware(1);
        Map<ToolSpecification, ToolExecutor> tools =
                MiddlewareComposition.compose(
                                noop,
                                Map.of(spec, base),
                                List.of(limit),
                                MiddlewareComposition.Options.defaults())
                        .tools();
        ToolExecutionRequest treq = ToolExecutionRequest.builder().name("t").arguments("{}").build();

        limit.beforeAgentRun(new AgentRunContext("m", "a"));
        assertEquals("ok", tools.get(spec).execute(treq, "m"));
        assertEquals(1, baseRuns.get());

        limit.beforeAgentRun(new AgentRunContext("m", "b"));
        assertEquals("ok", tools.get(spec).execute(treq, "m"));
        assertEquals(2, baseRuns.get());
    }

    @Test
    void maxCallsZeroBlocksImmediately() {
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("t")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        AtomicInteger baseRuns = new AtomicInteger();
        ToolExecutor base = (req, mem) -> {
            baseRuns.incrementAndGet();
            return "ok";
        };
        ChatModel noop =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
                    }
                };
        MaxToolCallsMiddleware limit = new MaxToolCallsMiddleware(0);
        Map<ToolSpecification, ToolExecutor> tools =
                MiddlewareComposition.compose(
                                noop,
                                Map.of(spec, base),
                                List.of(limit),
                                MiddlewareComposition.Options.defaults())
                        .tools();
        ToolExecutionRequest treq = ToolExecutionRequest.builder().name("t").arguments("{}").build();

        limit.beforeAgentRun(new AgentRunContext("m", "u"));
        String blocked = tools.get(spec).execute(treq, "m");
        assertTrue(blocked.contains("tool call limit"));
        assertEquals(0, baseRuns.get());
    }

    @Test
    void noArgConstructorUsesLibraryDefaultMax() {
        assertEquals(10, new MaxToolCallsMiddleware().maxCalls());
    }
}