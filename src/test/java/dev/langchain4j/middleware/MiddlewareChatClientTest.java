package dev.langchain4j.middleware;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiddlewareChatClientTest {

    @Test
    void wrapAppliesAgentMiddlewareToExistingDelegate() {
        AtomicInteger before = new AtomicInteger();
        AgentChat inner = (m, u) -> "reply";
        AgentChat client =
                MiddlewareChatClient.wrap(
                        inner,
                        new AgentMiddleware() {
                            @Override
                            public void beforeAgentRun(AgentRunContext context) {
                                before.incrementAndGet();
                            }
                        });
        assertEquals("reply", client.chat("mem", "hi"));
        assertEquals(1, before.get());
    }

    @Test
    void wrapVarargsSameAsList() {
        AtomicInteger n = new AtomicInteger();
        AgentChat inner = (a, b) -> "x";
        AgentChat w =
                MiddlewareChatClient.wrap(
                        inner,
                        new AgentMiddleware() {
                            @Override
                            public void beforeAgentRun(AgentRunContext context) {
                                n.incrementAndGet();
                            }
                        });
        w.chat("1", "2");
        assertEquals(1, n.get());
    }
}
