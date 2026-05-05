package dev.langchain4j.middleware.demo;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.middleware.AgentChat;
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.Map;
import java.util.Objects;

/**
 * Runnable demo: {@link MiddlewareAi} + {@link LoggingMiddleware} + a map-based tool. Uses a
 * <strong>real</strong> {@link OpenAiChatModel} when an API key is present in the environment; otherwise prints how to run
 * with a key and exits.
 *
 * <p>Environment (first non-blank wins):
 *
 * <ul>
 *   <li>{@code OPEN_AI_KEY} — preferred name for this demo</li>
 *   <li>{@code OPENAI_API_KEY} — common OpenAI convention</li>
 * </ul>
 *
 * Optional: {@code OPEN_AI_MODEL} (default {@code gpt-4o-mini}).
 *
 * <pre>{@code
 * export OPEN_AI_KEY=sk-...
 * mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.AgentMiddlewareDemo
 * }</pre>
 */
public final class AgentMiddlewareDemo {

    public static void main(String[] args) {
        ChatModel chatModel = createChatModelFromEnv();

        ToolSpecification capitalCityTool =
                ToolSpecification.builder()
                        .name("capital_city")
                        .description(
                                "Returns the capital city of a country. Call this when the user asks for a country's capital. "
                                        + "Pass JSON {\"country\": \"<country name>\"}.")
                        .parameters(
                                JsonObjectSchema.builder()
                                        .addStringProperty(
                                                "country",
                                                "Country name only, e.g. India, France, Japan.")
                                        .build())
                        .build();
        ToolExecutor capitalCityExecutor =
                (request, memoryId) -> {
                    // Demo implementation: deterministic capitals for a few countries (real apps use DB / APIs).
                    String raw = request.arguments();
                    String country = extractJsonStringField(raw, "country");
                    if (country == null) {
                        return "Unknown country (missing \"country\" in arguments).";
                    }
                    String c = country.toLowerCase();
                    if (c.contains("india")) {
                        return "New Delhi";
                    }
                    if (c.contains("france")) {
                        return "Paris";
                    }
                    if (c.contains("japan")) {
                        return "Tokyo";
                    }
                    return "Capital not in demo lookup for: " + country;
                };

        AgentChat client =
                MiddlewareAi.create(chatModel)
                        .middleware(new LoggingMiddleware("[demo]", 120))
                        .systemMessage(
                                "You are a helpful assistant. When the user asks for a country's capital, "
                                        + "you MUST call the tool capital_city with {\"country\": \"...\"}. "
                                        + "Then answer using the tool result.")
                        .tools(Map.of(capitalCityTool, capitalCityExecutor))
                        .build();

        System.out.println("=== MiddlewareAi + LoggingMiddleware + OpenAI tool demo ===");
        System.out.println("Registered tool: " + capitalCityTool.name());
        System.out.println();
        String reply =
                client.chat(
                        "session-demo-1",
                        "What is the capital city of the country India? Use the capital_city tool.");
        System.out.println();
        System.out.println("Final reply:");
        System.out.println("  " + reply);
        System.out.println();
        System.out.println(
                "Advanced: MiddlewareChatClient.wrap(inner::chat, mw) after MiddlewareComposition.compose(...).");
    }

    /**
     * Resolves API key from {@code OPEN_AI_KEY} or {@code OPENAI_API_KEY}; model from {@code OPEN_AI_MODEL} defaulting
     * to {@code gpt-4o-mini}.
     */
    private static ChatModel createChatModelFromEnv() {
        String apiKey = firstNonBlank(System.getenv("OPEN_AI_KEY"), System.getenv("OPENAI_API_KEY"));
        if (apiKey == null) {
            System.err.println("No API key found. Set one of:");
            System.err.println("  export OPEN_AI_KEY=sk-...");
            System.err.println("  export OPENAI_API_KEY=sk-...");
            System.err.println("Optional: export OPEN_AI_MODEL=gpt-4o-mini");
            System.exit(1);
            throw new IllegalStateException("unreachable");
        }
        String modelEnv = System.getenv("OPEN_AI_MODEL");
        String modelName =
                modelEnv != null && !modelEnv.isBlank() ? modelEnv.trim() : "gpt-4o-mini";
        return OpenAiChatModel.builder().apiKey(apiKey).modelName(modelName).build();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    /** Minimal {@code "country":"India"} style extractor for the demo executor (no JSON library in demo). */
    private static String extractJsonStringField(String json, String field) {
        Objects.requireNonNull(json, "json");
        String needle = "\"" + field + "\"";
        int key = json.indexOf(needle);
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key + needle.length());
        if (colon < 0) {
            return null;
        }
        int startQuote = json.indexOf('"', colon + 1);
        if (startQuote < 0) {
            return null;
        }
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) {
            return null;
        }
        return json.substring(startQuote + 1, endQuote);
    }
}
