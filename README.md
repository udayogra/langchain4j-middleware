# langchain4j-middleware

Middleware for [LangChain4j](https://github.com/langchain4j/langchain4j) **1.9.x**: add logging, retries, tool-call limits, model fallback, and summarization around your **`ChatModel`** and tools. Use **`MiddlewareAi`** to register middleware and **`build()`** an **`AgentChat`** (`chat(memoryId, userMessage)` → reply).

**Maven coordinates**

| Field        | Value                         |
|-------------|-------------------------------|
| **GroupId** | `dev.langchain4j.contrib`     |
| **ArtifactId** | `langchain4j-middleware`  |
| **Version** | `0.1.0-SNAPSHOT` (set before publish) |

**Dependency:** this module depends on the **`langchain4j`** artifact (not only `langchain4j-core`) so **`ToolExecutor`** is available for tool middleware.

---

## Stack middleware on `MiddlewareAi`

Think of it as three steps:

1. **`MiddlewareAi.create(chatModel)`** — start from your LangChain4j **`ChatModel`**.
2. **`.middleware(…)`** — add one or more middleware. Either chain (**`.middleware(log).middleware(retry)`**) or pass several at once (**`.middleware(log, retry)`**). The **first** one you list is the **outermost** (it wraps the rest for each user turn).
3. **`.build()`** — you get **`AgentChat`**. Call **`chat(memoryId, userMessage)`** with a conversation id and the user’s text; you get the assistant reply as a **`String`**. Memory, tools, system message, and RAG are optional builder calls—same familiar LangChain4j pieces, without writing your own assistant interface.

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

**Purpose:** Retries failed model and tool calls (via **`wrapLLMCall`** / **`wrapToolCall`**) with exponential backoff under the usual **`MiddlewareAi`** setup.

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

**Purpose:** Limits how many times tools are **actually executed** during one **`chat(...)`** call. The counter resets at the start of each new **`chat`**. If the model tries to run a tool again after the limit, that attempt is blocked (see **`OnLimit`** below).

**`MaxToolCallsMiddleware.OnLimit`** — you choose **one** of two behaviors when a call would exceed **`maxCalls`**:

| Value | What happens |
|--------|----------------|
| **`RETURN_MESSAGE`** | Default. The real tool does **not** run. A **fixed English message** is returned to the model **as if** it were the tool output, so the turn can continue and the model can stop calling tools and answer the user. |
| **`THROW`** | The real tool does **not** run. **`MaxToolCallsExceededException`** is thrown and the **`chat`** call fails. Use this when you want a hard error instead of a synthetic tool result. |

**Parameters**

| Constructor | Meaning |
|-------------|---------|
| **`MaxToolCallsMiddleware()`** | **`maxCalls`** **10**, **`onLimit`** **`RETURN_MESSAGE`**. |
| **`MaxToolCallsMiddleware(int maxCalls)`** | Same default **`onLimit`**. **`maxCalls`** — max real tool executions per **`chat`**; **≥ 0** (**0** blocks every tool invocation). |
| **`MaxToolCallsMiddleware(int maxCalls, OnLimit onLimit)`** | Set both the cap and **`RETURN_MESSAGE`** or **`THROW`**. |

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

**With `MiddlewareAi`:** list **`SummarizationMiddleware`** in **`.middleware(...)`** like the other middleware; long-term **`ChatMemory`** is updated so the compacted history **survives the next** **`chat`** turns.

**Parameters** (`SummarizationMiddleware.builder`)

| Step | Meaning |
|------|---------|
| **`builder(ChatModel summarizerModel)`** | **Required.** Small/cheap model that receives the summary prompt and returns summary text. |
| **`.trigger(...)`** | **Required** before **`build()`**. Decides **when** to summarize — see **Choosing a trigger** below. |
| **`.keepMessages(int)`** | How many **latest** messages to keep **unchanged** after summarizing; older ones are folded into the summary. Default **20**; must be **≥ 1**. |
| **`.summaryPrompt(String)`** | Template sent to the summarizer; must contain **`{messages}`**. Default is a built-in English template. |
| **`.summaryPrefix(String)`** | Prepended before summarizer output in the injected user message. Default: *"Here is a summary of the conversation to date:"*. |

**Choosing a trigger** — pass **one** of these to **`.trigger(...)`**:

- **`SummarizationTrigger.messages(N)`** — “There are **at least N** messages in the conversation.” Good default for learning: e.g. **`messages(10)`** means summarize once history is that long. **`N`** must be **≥ 1**.
- **`SummarizationTrigger.tokens(T)`** — “Estimated size is **at least about T tokens**” (a rough count, not the same as OpenAI’s billable tokens). **`T`** must be **≥ 1**.
- **`SummarizationTrigger.tokensAndMessages(T, N)`** — “**Both** must be true: at least **T** tokens **and** at least **N** messages.” Use this when you want **two** gates at once.

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

Each middleware implements **`AgentMiddleware`**; methods you do not override are **no-ops**. **List order:** the **first** middleware is **outermost** for compose-time **`wrapChatModel`** / **`wrapToolExecutor`**, and for each **`wrapLLMCall`** / **`wrapToolCall`** on the composed stack.

- **Agent turn:** `beforeAgentRun`, `afterAgentRun`, `onError` — around each outer **`chat`** from **`MiddlewareAi.build()`** (`onError` receives **`MiddlewarePhase`**).
- **LLM hooks:** `beforeLLMCall`, `afterLLMCall`, `wrapLLMCall` — around each model **`doChat`** on the composed **`ChatModel`**.
- **Tool hooks:** `beforeToolCall`, `afterToolCall`, `wrapToolCall` — around each tool **`execute`** when a tool map is configured.
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
