package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.middleware.context.MiddlewareErrorContext;
import dev.langchain4j.middleware.context.ModelCallAfterContext;
import dev.langchain4j.middleware.context.ModelCallContext;
import dev.langchain4j.middleware.context.ToolCallAfterContext;
import dev.langchain4j.middleware.context.ToolCallContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentMiddlewareChainTest {

    @Test
    void emptyMiddlewareReturnsSameDelegate() {
        AgentChat inner = (a, b) -> "x";
        assertSame(inner, AgentMiddlewareChain.wrapAgent(inner, List.of()));
    }

    @Test
    void beforeRunsInOrderAfterRunsReverse() {
        List<String> events = new ArrayList<>();
        AgentChat inner =
                (sessionId, userMessage) -> {
                    events.add("chat");
                    return "ok";
                };
        AgentMiddleware first =
                new AgentMiddleware() {
                    @Override
                    public void beforeAgentRun(AgentRunContext context) {
                        events.add("A-before");
                    }

                    @Override
                    public void afterAgentRun(AgentRunContext context, String assistantReply) {
                        events.add("A-after");
                    }
                };
        AgentMiddleware second =
                new AgentMiddleware() {
                    @Override
                    public void beforeAgentRun(AgentRunContext context) {
                        events.add("B-before");
                    }

                    @Override
                    public void afterAgentRun(AgentRunContext context, String assistantReply) {
                        events.add("B-after");
                    }
                };
        AgentChat wrapped = AgentMiddlewareChain.wrapAgent(inner, List.of(first, second));
        assertEquals("ok", wrapped.chat("m1", "hi"));
        assertEquals(List.of("A-before", "B-before", "chat", "B-after", "A-after"), events);
    }

    @Test
    void varargsWrapSameOrder() {
        List<String> events = new ArrayList<>();
        AgentChat inner = (s, u) -> "r";
        AgentMiddleware a =
                new AgentMiddleware() {
                    @Override
                    public void beforeAgentRun(AgentRunContext context) {
                        events.add("a");
                    }
                };
        AgentMiddleware b =
                new AgentMiddleware() {
                    @Override
                    public void beforeAgentRun(AgentRunContext context) {
                        events.add("b");
                    }
                };
        AgentMiddlewareChain.wrapAgent(inner, a, b).chat("x", "y");
        assertEquals(List.of("a", "b"), events);
    }

    @Test
    void onErrorInvokedAndExceptionPropagates() {
        List<String> events = new ArrayList<>();
        RuntimeException boom = new RuntimeException("x");
        AgentChat inner =
                (sessionId, userMessage) -> {
                    throw boom;
                };
        AgentMiddleware m =
                new AgentMiddleware() {
                    @Override
                    public void onError(MiddlewareErrorContext context) {
                        events.add("err");
                        assertEquals(boom, context.error());
                    }
                };
        AgentChat wrapped = AgentMiddlewareChain.wrapAgent(inner, List.of(m));
        assertThrows(RuntimeException.class, () -> wrapped.chat("s", "u"));
        assertEquals(List.of("err"), events);
    }

    @Test
    void contextCarriesMemoryIdAndUserMessage() {
        List<AgentRunContext> seen = new ArrayList<>();
        AgentChat inner = (sessionId, userMessage) -> "r";
        AgentMiddleware m =
                new AgentMiddleware() {
                    @Override
                    public void beforeAgentRun(AgentRunContext context) {
                        seen.add(context);
                    }
                };
        AgentMiddlewareChain.wrapAgent(inner, m).chat("mem", "hello");
        assertEquals(1, seen.size());
        assertEquals("mem", seen.get(0).memoryId());
        assertEquals("hello", seen.get(0).userMessage());
    }

    @Test
    void wrapChatModelFirstMiddlewareIsOutermost() {
        AtomicInteger outerChatCalls = new AtomicInteger();
        AtomicInteger innerChatCalls = new AtomicInteger();
        ChatModel base =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        innerChatCalls.incrementAndGet();
                        return ChatResponse.builder().aiMessage(AiMessage.from("base")).build();
                    }
                };
        AgentMiddleware outer =
                new AgentMiddleware() {
                    @Override
                    public ChatModel wrapChatModel(ChatModel delegate) {
                        return new ChatModel() {
                            @Override
                            public ChatResponse doChat(ChatRequest chatRequest) {
                                outerChatCalls.incrementAndGet();
                                return delegate.chat(chatRequest);
                            }
                        };
                    }
                };
        AgentMiddleware innerMw =
                new AgentMiddleware() {
                    @Override
                    public ChatModel wrapChatModel(ChatModel delegate) {
                        return new ChatModel() {
                            @Override
                            public ChatResponse doChat(ChatRequest chatRequest) {
                                return delegate.chat(chatRequest);
                            }
                        };
                    }
                };
        ChatModel composed = AgentMiddlewareChain.wrapChatModel(base, List.of(outer, innerMw));
        composed.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertEquals(1, outerChatCalls.get());
        assertEquals(1, innerChatCalls.get());
    }

    @Test
    void wrapChatModelMiddlewareCanRewriteRequestForDelegate() {
        AtomicReference<String> seenUser = new AtomicReference<>();
        ChatModel base =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        UserMessage um = (UserMessage) chatRequest.messages().get(0);
                        seenUser.set(um.singleText());
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
        AgentMiddleware prefixMw =
                new AgentMiddleware() {
                    @Override
                    public ChatModel wrapChatModel(ChatModel delegate) {
                        return new ChatModel() {
                            @Override
                            public ChatResponse doChat(ChatRequest chatRequest) {
                                UserMessage u = (UserMessage) chatRequest.messages().get(0);
                                ChatRequest rewritten =
                                        chatRequest.toBuilder()
                                                .messages(UserMessage.from("PREFIX:" + u.singleText()))
                                                .build();
                                return delegate.chat(rewritten);
                            }
                        };
                    }
                };
        ChatModel wrapped = AgentMiddlewareChain.wrapChatModel(base, List.of(prefixMw));
        wrapped.chat(ChatRequest.builder().messages(UserMessage.from("hello")).build());
        assertEquals("PREFIX:hello", seenUser.get());
    }

    @Test
    void wrapToolsAppliesMiddlewaresOutermostFirst() {
        List<String> events = new ArrayList<>();
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("t")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        ToolExecutor base = (req, mem) -> "done";
        AgentMiddleware outer =
                new AgentMiddleware() {
                    @Override
                    public ToolExecutor wrapToolExecutor(ToolSpecification s, ToolExecutor delegate) {
                        return (req, mem) -> {
                            events.add("outer");
                            return delegate.execute(req, mem);
                        };
                    }
                };
        AgentMiddleware innerMw =
                new AgentMiddleware() {
                    @Override
                    public ToolExecutor wrapToolExecutor(ToolSpecification s, ToolExecutor delegate) {
                        return (req, mem) -> {
                            events.add("inner");
                            return delegate.execute(req, mem);
                        };
                    }
                };
        Map<ToolSpecification, ToolExecutor> wrapped =
                AgentMiddlewareChain.wrapTools(Map.of(spec, base), List.of(outer, innerMw));
        assertEquals("done", wrapped.get(spec).execute(null, null));
        assertEquals(List.of("outer", "inner"), events);
    }

    @Test
    void wrapToolsEmptyListReturnsSameMapContent() {
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("x")
                        .description("y")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        ToolExecutor exec = (a, b) -> "z";
        Map<ToolSpecification, ToolExecutor> original = new LinkedHashMap<>(Map.of(spec, exec));
        Map<ToolSpecification, ToolExecutor> out = AgentMiddlewareChain.wrapTools(original, List.of());
        assertEquals(1, out.size());
        assertEquals("z", out.get(spec).execute(null, null));
    }

    @Test
    void wrapChatModelWithLlmHooksRunsBeforeWrapAfter() {
        List<String> events = new ArrayList<>();
        ChatModel base =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        events.add("base");
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
        AgentMiddleware mw =
                new AgentMiddleware() {
                    @Override
                    public void beforeLLMCall(ModelCallContext context) {
                        events.add("before");
                    }

                    @Override
                    public ChatResponse wrapLLMCall(ModelCallContext context, AgentMiddleware.LlmCallChain handler) {
                        events.add("wrap");
                        return handler.proceed(context);
                    }

                    @Override
                    public void afterLLMCall(ModelCallAfterContext context) {
                        events.add("after");
                    }
                };
        ChatModel hooked = AgentMiddlewareChain.wrapChatModelWithLlmHooks(base, List.of(mw));
        hooked.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertEquals(List.of("before", "wrap", "base", "after"), events);
    }

    @Test
    void wrapToolsWithToolHooksRunsBeforeWrapAfter() {
        List<String> events = new ArrayList<>();
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("t")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        ToolExecutor base = (req, mem) -> {
            events.add("exec");
            return "r";
        };
        AgentMiddleware mw =
                new AgentMiddleware() {
                    @Override
                    public void beforeToolCall(ToolCallContext context) {
                        events.add("beforeT");
                    }

                    @Override
                    public String wrapToolCall(ToolCallContext context, AgentMiddleware.ToolCallChain handler) {
                        events.add("wrapT");
                        return handler.execute(context);
                    }

                    @Override
                    public void afterToolCall(ToolCallAfterContext context) {
                        events.add("afterT");
                    }
                };
        Map<ToolSpecification, ToolExecutor> wrapped =
                AgentMiddlewareChain.wrapToolsWithToolHooks(Map.of(spec, base), List.of(mw));
        ToolExecutionRequest treq = ToolExecutionRequest.builder().name("t").arguments("{}").build();
        assertEquals("r", wrapped.get(spec).execute(treq, null));
        assertEquals(List.of("beforeT", "wrapT", "exec", "afterT"), events);
    }
}
