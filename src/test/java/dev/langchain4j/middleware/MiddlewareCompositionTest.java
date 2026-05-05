package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.middleware.context.ModelCallContext;
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

class MiddlewareCompositionTest {

    @Test
    void composeMatchesMiddlewareAiModelAndLlmHooks() {
        AtomicInteger llm = new AtomicInteger();
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("x")).build();
                    }
                };
        AgentMiddleware mw =
                new AgentMiddleware() {
                    @Override
                    public void beforeLLMCall(ModelCallContext context) {
                        llm.incrementAndGet();
                    }
                };
        List<AgentMiddleware> list = List.of(mw);
        MiddlewareComposition.Result r =
                MiddlewareComposition.compose(stub, Map.of(), list, MiddlewareComposition.Options.defaults());
        r.chatModel().chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("u")).build());
        assertEquals(1, llm.get());
    }

    @Test
    void composeWithToolHooksDisabledLeavesStaticWrapOnly() {
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("t")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        AtomicInteger beforeTool = new AtomicInteger();
        ToolExecutor base = (a, b) -> "ok";
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("m")).build();
                    }
                };
        AgentMiddleware mw =
                new AgentMiddleware() {
                    @Override
                    public void beforeToolCall(dev.langchain4j.middleware.context.ToolCallContext context) {
                        beforeTool.incrementAndGet();
                    }
                };
        MiddlewareComposition.Result r =
                MiddlewareComposition.compose(
                        stub,
                        Map.of(spec, base),
                        List.of(mw),
                        new MiddlewareComposition.Options(true, false));
        assertEquals(1, r.tools().size());
        r.tools().get(spec).execute(null, null);
        assertEquals(0, beforeTool.get());
    }

    @Test
    void composeChatModelMatchesComposeWithEmptyToolMap() {
        ChatModel stub =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
        List<AgentMiddleware> mw = List.of();
        MiddlewareComposition.Result a =
                MiddlewareComposition.composeChatModel(stub, mw, MiddlewareComposition.Options.defaults());
        MiddlewareComposition.Result b =
                MiddlewareComposition.compose(stub, Map.of(), mw, MiddlewareComposition.Options.defaults());
        assertEquals(0, a.tools().size());
        assertEquals(0, b.tools().size());
        assertEquals(
                "ok",
                a.chatModel()
                        .chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("u")).build())
                        .aiMessage()
                        .text());
        assertEquals(
                "ok",
                b.chatModel()
                        .chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("u")).build())
                        .aiMessage()
                        .text());
    }
}
