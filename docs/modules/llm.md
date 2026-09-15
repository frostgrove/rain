# rain-llm

A gateway over the application's Spring AI `ChatModel` bean: cluster-wide admission to the model server (slot rows per
pool in schema `rain_llm`, a ceiling per request class), breaker accounting of model calls only, an output token budget
from a declared token counter, and the `smoke-llm` command. Transport, provider retries, authentication and request
encoding stay Spring AI's.

Add it when the application asks a language model and several processes share one model server whose capacity is
limited.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-llm")
    testImplementation(testFixtures("com.gd.rain:rain-llm"))
}
```

It brings [rain-resilience](resilience.md), [rain-persistence](persistence.md) and Spring AI's `spring-ai-model`. The
application brings the `ChatModel` implementation of its provider. Building rain-llm itself generates its jOOQ code from
its migration. The test fixtures hold `ScriptedChatModel`.

## What it contributes

`RainLlmAutoConfiguration` — after Boot's jOOQ auto-configuration, `RainPersistenceAutoConfiguration` and
`RainResilienceAutoConfiguration`.

Always:

| Bean | Condition | What it is |
|---|---|---|
| `rainLlmErrorCodes` | — | `RainLlmErrorCodes` |
| `llmFaultTranslator` | — | `LlmFaultTranslator` |
| `smokeLlmCommand` | command `smoke-llm` | see [commands](#commands) |

When `rain.llm.enabled` is `true`:

| Bean | Condition | What it is |
|---|---|---|
| `llmSettings` | — | the validated section; refuses with every problem it finds |
| `llmSlotStore` | no other `LlmSlotStore` bean | `JooqLlmSlotStore` |
| `slotPause` | no other `SlotPause` bean | `SlotPause.SLEEP` |
| `llmSlots` | — | `LlmSlots`, holding slots as `<spring.application.name>/<pid>` |
| `tokenCounters` | — | the counters `rain.llm.models` declares, followed by every `TokenCounter` bean |
| `llmGateway` | no other `LlmGateway` bean | `LlmGateway` |
| `llmConfigurationCheck` | — | the bean-time rules below |
| `llmPoolProvisioning` | a role: `api`, `worker` or `seeder` | creates the budget row of every configured pool once all singletons exist |

## Configuration

`rain.llm` is optional. When it is present, `enabled` is stated; when it is enabled, every leaf that decides behaviour is
stated too. Stated values are validated whether the section is enabled or not.

| Property | Required or default | Meaning | Validation |
|---|---|---|---|
| `rain.llm.enabled` | required when the section is present | whether the gateway exists | a boolean |
| `rain.llm.model` | required when enabled | the model the `ChatModel` is asked for; also the key its token counter is looked up by | not blank |
| `rain.llm.timeout` | required when enabled | how long one model call may hold a slot: the lease a slot is written with | positive |
| `rain.llm.call-budget` | required when enabled | how long one ask may wait for a slot before it is refused | positive |
| `rain.llm.context-window` | required when enabled | the model's context window in tokens, prompt and answer together | at least 1 |
| `rain.llm.breaker` | required when enabled | the declared breaker model calls are accounted against | matches `^[a-z][a-z0-9_.-]{0,63}$`; a `BreakerDeclaration` bean declares it |
| `rain.llm.pools.<name>.bulk` | required for each pool; at least one pool when enabled | how many slots of the pool `BULK` asks may hold at once | at least 1; not above `interactive`; the pool name matches `^[a-z][a-z0-9_.-]{0,63}$` |
| `rain.llm.pools.<name>.interactive` | required for each pool | how many slots `INTERACTIVE` asks may hold at once | at least 1 |
| `rain.llm.models.<model>.encoding` | required for each declared model | the tokenizer encoding | one of `r50k_base`, `p50k_base`, `p50k_edit`, `cl100k_base`, `o200k_base` |
| `rain.llm.models.<model>.message-overhead` | required for each declared model | tokens of chat framing per message | not negative |
| `rain.llm.models.<model>.prompt-overhead` | required for each declared model | tokens of chat framing per prompt | not negative |
| `rain.llm.slot-poll-interval` | `200ms` | how often a waiting ask tries for a slot again; never longer than the ask has left | positive |

Interactive work may go at least as deep into a pool as bulk work, so `bulk` above `interactive` is a contradiction.

```yaml
rain:
  llm:
    enabled: true
    model: support-assistant
    timeout: 60s
    call-budget: 20s
    context-window: 32000
    breaker: llm
    pools:
      main:
        bulk: 4
        interactive: 8
    models:
      support-assistant:
        encoding: cl100k_base
        message-overhead: 4
        prompt-overhead: 3
  health:
    checks:
      breaker.llm: degrading
resilience4j:
  circuitbreaker:
    instances:
      llm:
        wait-duration-in-open-state: 30s
```

The breaker is configured and declared like any other ([rain-resilience](resilience.md)).

Bean-time problems, when enabled:

| Path | Code | When |
|---|---|---|
| `rain.llm.breaker` | `invalid` | no `BreakerDeclaration` bean declares the named breaker |
| `rain.llm.enabled` | `required` | the application has no `ChatModel` bean |
| `rain.llm.enabled` | `contradicts` | the application has several `ChatModel` beans and none is primary |
| `rain.llm.models.<model>` | `contradicts` | a model with two token counters |

## API

```kotlin
@Bean
fun llmBreaker(): BreakerDeclaration = BreakerDeclaration(BreakerName("llm"), healthCode = "llm")

class TicketSummaries(
    private val llm: LlmGateway,
) {
    fun draft(body: String): String {
        val messages = listOf(LlmMessage.system("Summarize the ticket in two sentences."), LlmMessage.user(body))
        val tokens =
            when (val budget = llm.outputBudget(messages, want = 256)) {
                is OutputBudget.Fits -> budget.tokens
                is OutputBudget.NoRoom -> throw Fault.validation(listOf(Violation(path("body"), RainErrorCodes.TOO_LONG)))
                is OutputBudget.NotEvaluated -> error(budget.reason)
            }
        return llm.complete("main", LlmClass.BULK, LlmRequest(messages, maxOutputTokens = tokens)).text
    }
}
```

### Asking

`LlmGateway.complete(pool, klass, request)` goes:

1. the pool is looked up — a pool the deployment does not configure is an `IllegalArgumentException` — and the prompt is
   built, customizers included; a failure here touches neither a slot nor the breaker;
2. a breaker that reads as withholding refuses at once with `LlmBreakerHeld`, before a slot is taken;
3. a slot of the pool is taken for the request's class, waiting at most `call-budget`;
4. the breaker's permission is reserved, atomically;
5. `ChatModel.call` runs; its failure is the only one the breaker is told about. An answer, even an unusable one, is a
   success for the breaker.

| Type | What it is |
|---|---|
| `LlmRequest(messages, temperature, maxOutputTokens)` | a provider-neutral ask; at least one message; `maxOutputTokens` at least 1; unstated options are the `ChatModel`'s own |
| `LlmMessage` | `system`, `user` or `assistant` content |
| `LlmClass` | `BULK` (background work) or `INTERACTIVE` (work a person waits for) |
| `LlmResponse(text, finishReason, usage)` | the answer; `finishReason` and `usage` when the provider reports them |
| `LlmRequestCustomizer` | adds provider-specific options to a request's `ChatOptions`, applied in bean order |

| Exception | When | Breaker |
|---|---|---|
| `LlmBreakerHeld(breaker, retryAfter)` | the breaker withholds calls; `retryAfter` is null when it was forced open | not told |
| `LlmBudgetExhausted(pool, klass)` | every slot of the class stayed taken for the whole call budget | not told |
| `LlmPoolMissing(pool)` | the pool is configured but `rain_llm.llm_budget` has no row for it | not told |
| `LlmInterrupted` | the thread was interrupted while waiting for a slot | not told |
| `LlmCallFailed(breaker, cause)` | `ChatModel.call` threw | told: a failure |
| `LlmEmptyAnswer` | the model answered with no generation or blank text | told: a success |

### Output budget

`LlmGateway.outputBudget(messages, want)` answers how many answer tokens fit next to the prompt in the context window:
`Fits(want)`, `NoRoom(promptTokens, shortBy)` — exactly how many too few — or `NotEvaluated(reason)` when no counter, or
more than one, is declared for the model. No characters-per-token ratio stands in for a missing counter, and no floor is
handed out without room.

`TokenCounter` (`model`, `count(messages)`) is declared per model, by `rain.llm.models.<model>` (`EncodingTokenCounter`:
the tokens of every message's content under the encoding, plus the stated framing overheads) or as an application bean.

### Slots

`LlmSlots.acquire(pool, klass, deadline)` tries to take a slot, and while the class's ceiling is reached tries again
every `slot-poll-interval`, never waiting past the deadline. A held `LlmSlot` is given back once when closed; a failure to
give it back is logged, not thrown, and the slot expires with its lease. `LlmSlotStore` is the statement port and
`SlotPause` the wait, both replaceable by a test.

### Test fixture

`ScriptedChatModel` is a deterministic `ChatModel`: every call takes the next scripted step (`answers(text, …)`,
`fails(failure)`, `then(step)`), records the prompt, and fails the test when no step is left.

## Commands

| Command | Roles | Declared properties | Output | Exit |
|---|---|---|---|---|
| `smoke-llm` | none | none | `smoke-llm: model <model> answered with <n> characters` on `out`, or `smoke-llm: <why>` on `err` | 0 when the model answered; 1 when `rain.llm.enabled` is not true, the application has no single `ChatModel`, or the call failed |

`smoke-llm` asks the model directly, with no slot and no breaker, so it answers whether the model server is reachable
even while every slot is taken or the breaker is open. The request goes through the application's customizers.

## Error codes

`RainLlmErrorCodes` (owner `rain-llm`), and how `LlmFaultTranslator` renders the exceptions:

| Exception | Fault |
|---|---|
| `LlmBudgetExhausted` | `503 llm_busy` — "the model is busy; try again" |
| `LlmBreakerHeld` | `503 llm_unavailable` — "the model is not answering; try again later", with `Retry-After` when the wait is known |
| `LlmCallFailed` | `503 llm_unavailable` |
| `LlmEmptyAnswer` | `503 llm_bad_answer` — "the model's answer could not be used; try again" |
| `LlmPoolMissing`, `LlmInterrupted` | not translated: `500 internal` |

## Health checks

None of its own. The breaker's `breaker.<name>` check comes from rain-resilience.

## Schema

`rain_llm`, migrated from `classpath:db/rain/llm`:

| Table | Holds |
|---|---|
| `llm_budget` | one row per pool: `pool` (primary key) and `taken`, the slots held against it, never negative |
| `llm_slots` | one row per held slot: `id` (primary key), `pool` (references `llm_budget`), `holder`, `taken_at`, `expires_at` |

Pool rows are not seeded by the migration. They are created from `rain.llm.pools` when a process with a role starts; a
pool with no row is refused explicitly at call time.

## Scale guarantees

- Taking a slot is one statement: sweeping the pool's expired slots (a range over `ix_llm_slots_expiry (pool, expires_at)`
  within one pool), reading the budget row `FOR UPDATE` by its primary key, deciding and inserting. Split into round trips,
  two processes would both read "one left" and both take it. `LlmSlotsIT` asserts the plan reads both indexes.
- Giving a slot back deletes one row by its primary key and lowers `taken` only for a row that was deleted.
- A lease is written with the database server's clock, so no two processes compare their own clocks, and the sweep repairs
  `taken` against the slots it removed.
- The rows a pool holds are bounded by its ceilings.

## What it does not do

- It does not choose a provider, set transport timeouts or retry a call; those are Spring AI's.
- It does not count slot contention, a missing pool row or an unusable answer as a model failure.
- It does not clamp a request to fit, estimate tokens from characters, or create a pool row at call time.
- It does not stream answers.
