package dev.langchain4j.middleware;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggingMiddlewareTest {

    @Test
    void llmHookPathWithLoggingMiddlewareCompletes() {
        ChatModel base =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
        ChatModel hooked =
                AgentMiddlewareChain.wrapChatModelWithLlmHooks(base, List.of(new LoggingMiddleware("[test]", 80)));
        ChatResponse r =
                hooked.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build());
        assertEquals("ok", r.aiMessage().text());
    }

    @Test
    void toolHookPathWithLoggingMiddlewareCompletes() {
        ToolSpecification spec =
                ToolSpecification.builder()
                        .name("t")
                        .description("d")
                        .parameters(JsonObjectSchema.builder().build())
                        .build();
        ToolExecutor base = (req, mem) -> "result";
        Map<ToolSpecification, ToolExecutor> wrapped =
                AgentMiddlewareChain.wrapToolsWithToolHooks(Map.of(spec, base), List.of(new LoggingMiddleware()));
        ToolExecutionRequest treq = ToolExecutionRequest.builder().name("t").arguments("{}").build();
        assertEquals("result", wrapped.get(spec).execute(treq, "mem"));
    }
}
