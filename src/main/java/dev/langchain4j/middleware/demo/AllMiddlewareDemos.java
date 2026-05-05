package dev.langchain4j.middleware.demo;

/**
 * Runs {@link RetryMiddlewareDemo}, {@link MaxToolCallsMiddlewareDemo}, {@link ModelFallbackMiddlewareDemo},
 * {@link SummarizationMiddlewareDemo}, then {@link AgentMiddlewareDemo} in one JVM (no API key until the OpenAI demo).
 *
 * <pre>{@code
 * mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.AllMiddlewareDemos
 * }</pre>
 */
public final class AllMiddlewareDemos {

    public static void main(String[] args) {
        RetryMiddlewareDemo.main(new String[0]);
        System.out.println();
        System.out.println("────────────────────────────────────────");
        System.out.println();
        MaxToolCallsMiddlewareDemo.main(new String[0]);
        System.out.println();
        System.out.println("────────────────────────────────────────");
        System.out.println();
        ModelFallbackMiddlewareDemo.main(new String[0]);
        System.out.println();
        System.out.println("────────────────────────────────────────");
        System.out.println();
        SummarizationMiddlewareDemo.main(new String[0]);
        System.out.println();
        System.out.println("────────────────────────────────────────");
        System.out.println();
        AgentMiddlewareDemo.main(new String[0]);
    }
}
