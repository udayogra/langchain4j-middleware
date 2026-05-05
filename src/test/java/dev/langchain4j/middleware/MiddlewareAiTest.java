package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.middleware.context.ModelCallContext;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiddlewareAiTest {

    @Test
    void firesAgentAndLlmHooks() {
        AtomicInteger agentRuns = new AtomicInteger();
        AtomicInteger llmRuns = new AtomicInteger();
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
        AgentMiddleware mw =
                new AgentMiddleware() {
                    @Override
                    public void beforeAgentRun(AgentRunContext context) {
                        agentRuns.incrementAndGet();
                    }

                    @Override
                    public void beforeLLMCall(ModelCallContext context) {
                        llmRuns.incrementAndGet();
                    }
                };
        AgentChat client = MiddlewareAi.create(stub).middleware(mw).build();
        client.chat("memory-1", "hello");
        assertEquals(1, agentRuns.get());
        assertEquals(1, llmRuns.get());
    }

    @Test
    void llmInvocationHooksCanBeDisabled() {
        AtomicInteger llmRuns = new AtomicInteger();
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
        AgentMiddleware mw =
                new AgentMiddleware() {
                    @Override
                    public void beforeLLMCall(ModelCallContext context) {
                        llmRuns.incrementAndGet();
                    }
                };
        AgentChat client =
                MiddlewareAi.builder(stub).middleware(mw).llmInvocationHooks(false).build();
        client.chat("m", "x");
        assertEquals(0, llmRuns.get());
    }

    @Test
    void emptyMiddlewareStillBuilds() {
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("z")).build();
                    }
                };
        AgentChat client = MiddlewareAi.builder(stub).build();
        assertEquals("z", client.chat("a", "b"));
    }

    @Test
    void buildWithToolsDoesNotThrow() {
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("plain")).build();
                    }
                };
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("noop")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        ToolExecutor exec = (req, mem) -> "tool";
        AgentChat client =
                MiddlewareAi.builder(stub)
                        .tools(Map.of(spec, exec))
                        .middleware(
                                new AgentMiddleware() {
                                    @Override
                                    public void beforeToolCall(
                                            dev.langchain4j.middleware.context.ToolCallContext context) {
                                        // no-op; chain must compose
                                    }
                                })
                        .build();
        assertEquals("plain", client.chat("id", "hi"));
    }

    @Test
    void middlewaresRunInChainOrderForAgentHook() {
        List<String> order = new ArrayList<>();
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("r")).build();
                    }
                };
        AgentChat client =
                MiddlewareAi.builder(stub)
                        .middlewares(
                                List.of(
                                        new AgentMiddleware() {
                                            @Override
                                            public void beforeAgentRun(AgentRunContext context) {
                                                order.add("A");
                                            }
                                        },
                                        new AgentMiddleware() {
                                            @Override
                                            public void beforeAgentRun(AgentRunContext context) {
                                                order.add("B");
                                            }
                                        }))
                        .build();
        client.chat("m", "u");
        assertEquals(List.of("A", "B"), order);
    }

    @Test
    void middlewareVarargsRunsBeforeAgentInDeclarationOrder() {
        List<String> order = new ArrayList<>();
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("r")).build();
                    }
                };
        AgentChat client =
                MiddlewareAi.builder(stub)
                        .middleware(
                                new AgentMiddleware() {
                                    @Override
                                    public void beforeAgentRun(AgentRunContext context) {
                                        order.add("first");
                                    }
                                },
                                new AgentMiddleware() {
                                    @Override
                                    public void beforeAgentRun(AgentRunContext context) {
                                        order.add("second");
                                    }
                                })
                        .build();
        client.chat("m", "u");
        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void middlewaresVarargsReplacesList() {
        List<String> order = new ArrayList<>();
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("r")).build();
                    }
                };
        AgentMiddleware only =
                new AgentMiddleware() {
                    @Override
                    public void beforeAgentRun(AgentRunContext context) {
                        order.add("x");
                    }
                };
        AgentChat client =
                MiddlewareAi.builder(stub)
                        .middleware(only)
                        .middlewares(
                                new AgentMiddleware() {
                                    @Override
                                    public void beforeAgentRun(AgentRunContext context) {
                                        order.add("y");
                                    }
                                })
                        .build();
        client.chat("m", "u");
        assertEquals(List.of("y"), order);
    }

    @Test
    void contentRetrieverOnBuilder() {
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("with-rag")).build();
                    }
                };
        ContentRetriever retriever = query -> List.of();
        AgentChat client = MiddlewareAi.builder(stub).contentRetriever(retriever).build();
        assertEquals("with-rag", client.chat("m", "q"));
    }
}
