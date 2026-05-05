# langchain4j-middleware

Composable **middleware** for [LangChain4j](https://github.com/langchain4j/langchain4j) **1.9.x**: one Java **`AgentMiddleware`** interface (defaults for unused methods), wrapping **`ChatModel`** and tools, plus optional per-call hooks.

**How you use it:** **`MiddlewareAi.create(rawChatModel).middleware(...).build()`** → **`AgentChat`** (see [Stack middleware](#stack-middleware-on-middlewareai)).

**Maven coordinates**

| Field        | Value                         |
|-------------|-------------------------------|
| **GroupId** | `dev.langchain4j.contrib`     |
| **ArtifactId** | `langchain4j-middleware`  |
| **Version** | `0.1.0-SNAPSHOT` (set before publish) |

**Dependency:** this module depends on the **`langchain4j`** artifact (not only `langchain4j-core`) so **`ToolExecutor`** is available for tool middleware.

---

## Stack middleware on `MiddlewareAi`

Add middleware with **`.middleware(...)`**. You can chain calls (**`.middleware(log).middleware(retry)`**) or pass several at once (**`.middleware(log, retry)`**) — same order rules. The **first** middleware you register is **outermost** (it sees the turn first and wraps the composed **`ChatModel`** / tools on the outside).

**`.build()`** returns **`AgentChat`**: one method **`chat(memoryId, userMessage)`** → assistant **`String`**. Under the hood **`MiddlewareAi`** uses LangChain4j **`AiServices`** with **`MiddlewareChatService`** (`@MemoryId` + `@UserMessage`), your **`ChatModel`**, optional **`.tools(...)`**, memory, RAG, etc. So it is the usual LangChain4j assistant stack, but the **type you hold** is **`AgentChat`**, not a **`ChatModel`** and not a custom multi-method assistant interface you defined yourself.

```java
import dev.langchain4j.middleware.AgentChat;
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.middleware.retry.RetryMiddleware;

AgentChat client =
    MiddlewareAi.create(chatModel)
        .middleware(
            new LoggingMiddleware("[app]", 200),   // outermost
            new RetryMiddleware())
        .systemMessage("You are a helpful assistant.")
        // .tools(toolMap)
        .build();

String reply = client.chat("session-1", "Hello");
```

The sections below describe each built-in middleware and its constructor parameters.

---

## Logging — `LoggingMiddleware`

**Purpose:** SLF4J **INFO** logs for agent / LLM / tool lifecycle; **ERROR** on failures with phase context.

**Parameters**

| Constructor | Meaning |
|-------------|---------|
| **`LoggingMiddleware()`** | Prefix **`"[AI Middleware]"`**, string preview length **120** (per logged field). |
| **`LoggingMiddleware(String prefix, int previewChars)`** | **`prefix`** — non-null, prepended to log lines. **`previewChars`** — max characters per string preview; must be **≥ 1**. |

```java
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.logging.LoggingMiddleware;

var client = MiddlewareAi.create(model)
    .middleware(new LoggingMiddleware("[billing]", 150))
    .systemMessage("You are a helpful assistant.")
    .build();

String reply = client.chat("session-1", "Hello");
```

---

## Retry — `RetryMiddleware`

**Purpose:** Retries failed model and tool calls (via **`wrapLLMCall`** / **`wrapToolCall`**) with exponential backoff. Those hooks must stay enabled (**`MiddlewareAi`** turns both on by default; turn them off only if you use **`.llmInvocationHooks(false)`** / **`.toolInvocationHooks(false)`**).

**Parameters**

| Constructor / arg | Meaning |
|-------------------|---------|
| **`RetryMiddleware()`** | **`maxRetries`** 3, **`initialDelayMillis`** 1000, **`backoffMultiplier`** 2.0, **`maxDelayMillis`** 30_000, **`nonRetryableTypesCommaSeparated`** `"IllegalArgumentException"` (no retry for that simple name). |
| **`RetryMiddleware(int maxRetries, long initialDelayMillis, double backoffMultiplier, long maxDelayMillis, String nonRetryableTypesCommaSeparated)`** | **`maxRetries`** — retries after first failure (total attempts ≤ **`maxRetries + 1`**); must be **≥ 0**. **`initialDelayMillis`** — sleep before first retry; **≥ 0**. **`backoffMultiplier`** — applied after each failure; must be **≥ 1.0**. **`maxDelayMillis`** — cap between attempts; **≥ 0**. **`nonRetryableTypesCommaSeparated`** — exception **simple class names**, comma-separated, case-insensitive; matching types fail immediately (blank / empty means none). |

```java
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.retry.RetryMiddleware;

var client = MiddlewareAi.create(model)
    .middleware(new RetryMiddleware(2, 100L, 2.0, 5_000L, ""))
    .tools(toolMap)
    .build();
```

**Runnable demo:** `dev.langchain4j.middleware.demo.RetryMiddlewareDemo`

```bash
mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.RetryMiddlewareDemo
```

---

## Max tool calls — `MaxToolCallsMiddleware`

**Purpose:** Caps **tool executions** per outer **`chat`**; resets each turn. Over limit: synthetic tool result (**`RETURN_MESSAGE`**) or **`MaxToolCallsExceededException`** (**`THROW`**).

**Parameters**

| Constructor | Meaning |
|-------------|---------|
| **`MaxToolCallsMiddleware()`** | **`maxCalls`** 10, **`onLimit`** **`RETURN_MESSAGE`**. |
| **`MaxToolCallsMiddleware(int maxCalls)`** | Same **`onLimit`** as default; **`maxCalls`** — max real tool runs per agent turn after **`beforeAgentRun`**; **≥ 0** (**0** blocks every tool). |
| **`MaxToolCallsMiddleware(int maxCalls, OnLimit onLimit)`** | **`onLimit`** — **`RETURN_MESSAGE`** (synthetic tool result string) or **`THROW`** (**`MaxToolCallsExceededException`**). |

```java
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.maxtoolcalls.MaxToolCallsMiddleware;

var client = MiddlewareAi.create(model)
    .middleware(new MaxToolCallsMiddleware(5, MaxToolCallsMiddleware.OnLimit.RETURN_MESSAGE))
    .tools(toolMap)
    .build();
```

**Runnable demo:** `dev.langchain4j.middleware.demo.MaxToolCallsMiddlewareDemo`

```bash
mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.MaxToolCallsMiddlewareDemo
```

---

## Model fallback — `ModelFallbackMiddleware`

**Purpose:** If the **primary** model throws inside **`wrapLLMCall`**, tries **fallback** **`ChatModel`** instances in order via **`doChat`** until one succeeds.

**Ordering:** put **`ModelFallbackMiddleware`** **first** if you want fallback **`doChat`** paths to skip inner **`wrapLLMCall`** middleware on those retries (see class Javadoc).

**Parameters**

| Constructor | Meaning |
|-------------|---------|
| **`ModelFallbackMiddleware(ChatModel first, ChatModel... more)`** | **`first`** — first fallback when the **primary** (argument to **`MiddlewareAi.create`**) fails; **`more`** — additional fallbacks in order. All non-null. |
| **`ModelFallbackMiddleware(List<ChatModel> fallbackModels)`** | Non-empty list; same try order as list iteration. |

```java
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.middleware.modelfallback.ModelFallbackMiddleware;

ChatModel cheapBackup = ...;

var client = MiddlewareAi.create(primaryModel)
    .middleware(
        new ModelFallbackMiddleware(cheapBackup),
        new LoggingMiddleware("[app]", 200))
    .build();
```

**Runnable demo:** `dev.langchain4j.middleware.demo.ModelFallbackMiddlewareDemo`

```bash
mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.ModelFallbackMiddlewareDemo
```

---

## Summarization — `SummarizationMiddleware` + `SummarizingChatMemory`

**Purpose:** When a **trigger** (message count and/or approximate tokens) fires, older messages are summarized by a **separate** summarizer **`ChatModel`**, replaced by one synthetic **user** message plus a **verbatim tail** (**`keepMessages`**). Tool-call boundaries are respected when cutting.

Include **`SummarizationMiddleware`** in **`.middleware(...)`**; **`MiddlewareAi.build()`** **hoists** it off the LLM chain and wraps **`ChatMemory`** / **`ChatMemoryProvider`** with **`SummarizingChatMemory`** so compaction **persists**.

**Parameters** (`SummarizationMiddleware.builder`)

| Step | Meaning |
|------|---------|
| **`builder(ChatModel summarizerModel)`** | **Required.** Small/cheap model that receives the summary prompt and returns summary text. |
| **`.trigger(SummarizationTrigger t)`** | **Required** before **`build()`**. When to compact: **`SummarizationTrigger.messages(n)`**, **`.tokens(n)`**, or **`.tokensAndMessages(tokens, messages)`** (if both set, **both** must be satisfied). Thresholds must be **> 0** where used. |
| **`.keepMessages(int)`** | Trailing messages kept verbatim after summarization. Default **20**; must be **≥ 1** in the built middleware. |
| **`.summaryPrompt(String)`** | Template sent to the summarizer; must contain **`{messages}`**. Default is a built-in English template. |
| **`.summaryPrefix(String)`** | Prepended before summarizer output in the injected user message. Default: *"Here is a summary of the conversation to date:"*. |

```java
import dev.langchain4j.middleware.MiddlewareAi;
import dev.langchain4j.middleware.logging.LoggingMiddleware;
import dev.langchain4j.middleware.summarization.SummarizationMiddleware;
import dev.langchain4j.middleware.summarization.SummarizationTrigger;

var client =
    MiddlewareAi.create(primaryModel)
        .middleware(
            new LoggingMiddleware("[app]", 200),
            SummarizationMiddleware.builder(summarizerModel)
                .trigger(SummarizationTrigger.messages(10))
                .keepMessages(4)
                .build())
        .build();
```

**Runnable demo:** `dev.langchain4j.middleware.demo.SummarizationMiddlewareDemo`

```bash
mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.SummarizationMiddlewareDemo
```

---

## `AgentMiddleware` reference (custom middleware & debugging)

Each middleware implements **`AgentMiddleware`**; methods you do not override are **no-ops**. **List order:** the **first** middleware is **outermost** for compose-time **`wrapChatModel`** / **`wrapToolExecutor`**, and—when hooks are enabled—for each **`wrapLLMCall`** / **`wrapToolCall`**.

- **Agent turn:** `beforeAgentRun`, `afterAgentRun`, `onError` — around each outer **`chat`** from **`MiddlewareAi.build()`** (`onError` receives **`MiddlewarePhase`**).
- **LLM hooks:** `beforeLLMCall`, `afterLLMCall`, `wrapLLMCall` — when LLM invocation hooks are enabled (default **`true`** on **`MiddlewareAi`**).
- **Tool hooks:** `beforeToolCall`, `afterToolCall`, `wrapToolCall` — when tool invocation hooks are enabled (default **`true`** on **`MiddlewareAi`**).
- **Compose-time:** `wrapChatModel`, `wrapToolExecutor` — applied when the middleware list (or tool map) is non-empty.

---

## Runnable demos (overview)

| Main class | What it exercises |
|------------|-------------------|
| **`RetryMiddlewareDemo`** | Retry on LLM + tool failures (stubs). |
| **`MaxToolCallsMiddlewareDemo`** | Tool call cap + synthetic limit message. |
| **`ModelFallbackMiddlewareDemo`** | Primary fails → fallback succeeds. |
| **`SummarizationMiddlewareDemo`** | **`MiddlewareAi`** + hoisted summarization memory + stubs. |
| **`AgentMiddlewareDemo`** | Real **`OpenAiChatModel`** if **`OPEN_AI_KEY`** or **`OPENAI_API_KEY`** is set + tool. |
| **`AllMiddlewareDemos`** | Runs the above demos in sequence in one JVM. |

```bash
cd langchain4j-middleware
mvn -q compile exec:java -Dexec.mainClass=dev.langchain4j.middleware.demo.AllMiddlewareDemos
```

---

## Build and test

```bash
cd langchain4j-middleware
mvn test
```
