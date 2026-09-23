# zio-typesafe-ai

[![javadocs.dev](https://www.javadocs.dev/com.jamesward/zio-typesafe-ai_3/badge.svg)](https://www.javadocs.dev/com.jamesward/zio-typesafe-ai_3/latest)

A Scala 3 / ZIO library for [TypeSafe AI](https://docs.typesafe.ai/introduction)'s
Jev / System One API — a "System One model" that answers typed, atomic
questions about a piece of state instead of generating text.

- Typed end-to-end. No raw JSON, no stringly-typed answer lookups.
- Ask several questions in one round-trip via a `NamedTuple`; the typed
  answer comes back keyed and shaped exactly like the questions you asked.
- Illegal states are unrepresentable: `ChoiceCriteria` / `ScoreCriteria`
  can't be built out of range (1–255 options, 2–10 levels), and every
  `noul` / `confidence` / probability is a `Probability` — a `Double`
  provably within `[0.0, 1.0]`.
- Built on ZIO HTTP's `Client`.

## Install

```scala
libraryDependencies += "com.jamesward" %% "zio-typesafe-ai" % "<version>"
```

## Configure

`TypeSafeAI.Client.live` reads the environment variables the official
Python/JavaScript SDKs use:

| Var                      | Required | Default       |
| ------------------------- | -------- | ------------- |
| `TYPESAFE_API_KEY`        | yes      | —             |
| `TYPESAFE_DEFAULT_MODEL`  | no       | `jev-latest`  |

```scala
import com.jamesward.zio_typesafe_ai.TypeSafeAI
import zio.http.Client

program.provide(Client.default, TypeSafeAI.Client.live)
```

Or construct the layer explicitly:

```scala
TypeSafeAI.Client.layer(TypeSafeAI.ApiKey("…"), TypeSafeAI.ModelId.JevLatest)
```

### Client middleware

Decorate a client layer with composable middleware using the same `@@` / `++`
shape as ZIO HTTP. Composition applies the left middleware around the right:

```scala
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*

val client = Client.live @@ Middleware.logging()
val composed = Client.live @@ (metricsMiddleware ++ Middleware.logging())
```

`Middleware.make` can wrap the exchange effect to add retries, tracing,
metrics, policy checks, or other cross-cutting behavior. It receives canonical
request JSON and an effect producing a response with canonical JSON:

```scala
val retryRateLimits = Middleware.make: (_, next) =>
  next.catchSome:
    case _: Error.RateLimit => next
```

`Middleware.logging()` logs complete requests, canonical responses, failures,
and latency. Configure request and response body inclusion with
`Middleware.LoggingConfig`. Headers and bearer tokens are never included, but
state and model output may still be sensitive, so logging is opt-in.

`ExchangeObserver` and `Client.observed` remain available for compatibility and
are implemented through middleware. Observed responses are decoded and
canonically re-encoded rather than byte-exact HTTP response text. Callback
defects and self-interruption are logged and suppressed without changing the
original exchange result.

## Asking questions

Jev has three question primitives — see
[docs.typesafe.ai/primitives](https://docs.typesafe.ai/primitives):

- **`Question.Noul`**   — "is this true?" → a calibrated [`Probability`].
- **`Question.Choice`** — "which of these?" → the selected option plus the
  full probability distribution.
- **`Question.Score`**  — "which level?" → a probability-weighted mean over
  an ordered scale.

Bundle one or more questions in a `NamedTuple` passed to `TypeSafeAI.ask`.
The keys become both the wire question ids *and* the compile-time field
names of the typed result — ask for `isUrgent` and `department`, get back
a value with `.isUrgent: Probability` and `.department: ChoiceAnswer`.

```scala
import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*

val message =
  "Hi, I've been trying to connect my Stripe account for 3 days and it " +
  "keeps failing. I'm losing sales. Please help ASAP."

val department: ChoiceCriteria =
  ChoiceCriteria(
    "billing"   -> "Payment or subscription issues",
    "technical" -> "Bugs or integration problems",
    "sales"     -> "Pricing or account questions",
  ).getOrElse(throw new IllegalArgumentException("bad criteria"))

val frustration: ScoreCriteria =
  ScoreCriteria(
    "Calm, just stating facts",
    "Frustrated but civil",
    "Very angry, strong language",
  ).getOrElse(throw new IllegalArgumentException("bad criteria"))

val program = ask(
  message,
  (
    isUrgent    = Question.Noul("Does this message express urgency or time pressure?"),
    department  = Question.Choice("Which team should handle this?", department),
    frustration = Question.Score("How frustrated does the customer sound?", frustration),
  ),
).answers
// : ZIO[Client, Error, (isUrgent: Probability, department: ChoiceAnswer, frustration: ScoreAnswer)]

program.debug("answers").provide(zio.http.Client.default, TypeSafeAI.Client.live)
```

### Advanced structured primitives

Every TypeSafe [`EntryType`](https://docs.typesafe.ai/primitives/advanced) shape is preserved on the wire:

| Field | Scala values |
| --- | --- |
| Choice/Score/Noul `instructions` | Any `Schema` value: `String`, a case class or `Map` (object), a collection (array), or `Option.empty[A]` (JSON `null`) |
| Choice option descriptions | `Content` containing any structured value, or `null` |
| Score level descriptions | `Content` containing any structured value, or `null` |
| Noul `true` / `false` descriptions | `Content` containing any structured value, or `null`; omit the entire criteria object by not passing it to `Question.Noul` |

This means schemas, taxonomies, database rows, rubrics, and boundary examples can be passed as data rather than interpolated into prompts:

```scala
import zio.schema.{Schema, derived}

case class FieldCheck(
  question: String,
  compare: List[String],
  focus: String,
) derives Schema

case class Boundary(definition: String, examples: List[String]) derives Schema

val routeCriteria = ChoiceCriteria.fromContent(Map(
  "billing" -> Content(Boundary("A payment or subscription issue", List("card declined"))),
  "technical" -> Content(Map(
    "includes" -> List("bugs", "integration failures"),
    "excludes" -> List("pricing questions"),
  )),
  "other" -> (null: Content | Null),
)).toOption.get

val severityCriteria = ScoreCriteria.fromContent(List(
  Content(Boundary("No customer impact", List("cosmetic typo"))),
  Content(List("degraded", "workaround available")),
  null,
)).toOption.get

val identityBoundary = NoulCriteria.fromContent(
  whenTrue = Content(Boundary("The identity conflicts with the domain", List("Acme via an unrelated domain"))),
  whenFalse = null,
)

val questions = (
  route = Question.Choice(
    FieldCheck(
      "Which team owns this issue?",
      List("ticket.subject", "ticket.body"),
      "Use the most specific matching rubric.",
    ),
    routeCriteria,
  ),
  severity = Question.Score(List("Assess customer impact", "Prefer observed evidence"), severityCriteria),
  identityConflict = Question.Noul(
    Map("compare" -> List("ticket.sender.display_name", "ticket.sender.email")),
    identityBoundary,
  ),
)
```

The same API also accepts dynamic `zio.json.ast.Json` values. Import ZIO Schema's JSON-AST schema when the state or instructions are assembled at runtime; use `Content` for dynamic criteria entries:

```scala
import zio.json.ast.Json
import zio.schema.codec.json.schemaJson

val dynamicInstructions: Json = Json.Obj(
  "question" -> Json.Str("Which route applies?"),
  "compare" -> Json.Arr(Json.Str("subject"), Json.Str("body")),
)

val dynamicCriteria = ChoiceCriteria.fromContent(Map(
  "billing" -> Content(Json.Obj("examples" -> Json.Arr(Json.Str("card declined")))),
  "other" -> (null: Content | Null),
)).toOption.get

val dynamicQuestion = Question.Choice(dynamicInstructions, dynamicCriteria)
val dynamicState: Json = Json.Obj("subject" -> Json.Str("Payment failed"))
val dynamicRequest = ask(dynamicState, (route = dynamicQuestion))
```

Both paths use the same `Schema[T]` encoding boundary: domain values retain their concrete type in the Scala API, while `Json` supports runtime-defined object, array, string, and null shapes without defining a case class.

For a taxonomy walk, build each `ChoiceCriteria` from the current node's children and put each child subtree in its `Content` description. Use the returned probability distribution to decide whether to continue one branch or retain several candidates.

`ask(state, questions)` requires `Client` in the environment and a
non-empty `NamedTuple` of `Question`s; passing anything else is a compile
error. `state` and every question's `instructions` are generic in a
`Schema` type parameter — `ask[S: Schema](state: S, ...)`,
`Question.Noul[S: Schema](instructions: S, ...)` — so a plain string
works out of the box, and any other `Schema`-derived record, taxonomy,
or database row works exactly the same way, with `S` inferred from
whatever you pass. `question.instructions` reads back as that exact
value, precisely typed — no wrapper, no decode step.

### `.answers` vs `.run`

`.answers` returns just the typed `NamedTuple`. `.run` wraps it in a
`Result`, which also carries the responding model and token usage:

```scala
ask(message, (isUrgent = Question.Noul("Urgent?"))).run
// : ZIO[Client, Error, Result[(isUrgent: Probability)]]
//   Result(answers, model, usage)
```

### Overriding the model

```scala
ask(message, tools).model(TypeSafeAI.ModelId("jev-2025-11")).answers
```

## Criteria smart constructors

`ChoiceCriteria` and `ScoreCriteria` validate their shape at
construction — Jev accepts 1–255 choice options and 2–10 score levels,
so building one outside that range is a compile-valid but always-`Left`
call, not a runtime exception surfacing later on the wire:

```scala
ChoiceCriteria("yes" -> "...", "no" -> "...")           // Either[String, ChoiceCriteria]
ChoiceCriteria.fromContent(structuredOptionsMap)         // for object/array descriptions

ScoreCriteria("low", "medium", "high")                   // Either[String, ScoreCriteria]
ScoreCriteria.fromContent(structuredLevelsList)          // for object/array levels
```

A `null` `Choice` option description means "the option name is
self-explanatory" (matches the wire's own `null` convention).

## Jev-driven state-machine loops

`TypeSafeAI.loop` repeatedly presents a host-generated finite action set to
Jev as one `Choice`, handles the selected action, and either continues with a
new state or completes. State and action types remain application-defined.
The wire request contains the serialized `Content` state view, option ids and
descriptions, configurable choice instructions, model id, and question
metadata; the host action values themselves never cross the wire.

```scala
val request = TypeSafeAI.loop[Int, String, Any, Nothing, Int](0)(
  state => Content(state),
  _ => ZIO.succeed(NonEmptyChunk(
    LoopOption.text("increment", "increment", "Increase the state by one."),
    LoopOption.text("finish", "finish", "Return the current state."),
  )),
) { (state, action) =>
  action match
    case "increment" => ZIO.succeed(LoopStep.Continue(state + 1))
    case "finish"    => ZIO.succeed(LoopStep.Done(state))
}

request.maxIterations(10).run
// ZIO[Client, Error, LoopResult[Int]]
```

`LoopResult` contains the final output, every `LoopTurn` (selected choice,
full `ChoiceAnswer`, usage, and Jev request latency), aggregate Jev usage, and
aggregate Jev request latency. Option ids must be non-empty and unique. Unknown
model choices, invalid options/policies, and non-positive iteration limits fail
with `Error.InvalidLoop`; exhausting the limit fails with
`Error.MaxIterations`.

The default Choice instruction sentence is stable and unchanged. Replace it
with any `Schema` value—objects and arrays remain structured on the wire—and
configure retry/observation independently:

```scala
val observer = LoopObserver.make[MyError, Output] {
  case LoopObservation.OptionsGenerated(iteration, state, options) => recordOptions(iteration, state, options)
  case LoopObservation.Decision(turn)                              => recordDecision(turn)
  case LoopObservation.Completion(result)                          => recordCompletion(result)
  case LoopObservation.Failure(cause, turns)                       => recordFailure(cause, turns)
}

request
  .choiceInstructions(Map("goal" -> "finish", "rule" -> "pick one legal option"))
  .retryEachTurn(LoopRetryPolicy(maxRetries = 2, initialDelay = Duration.Zero))
  .observe(observer)
  .run
```

Semantic event order is validated options → `OptionsGenerated` → Jev →
`Decision` → the handler → recursion or `Completion`. A failed execution emits
exactly one terminal `Failure` with all successfully selected turns; its cause
is `Cause[Error | E]`, so host error types do not need to extend `Throwable`.
Callbacks are best-effort and defect/interruption-isolated.

Retries are opt-in and apply only to the current turn's Jev request. Only
`RateLimit`, `ServiceOverloaded`, and `InternalServer` are retryable; option
generation, state rendering, observers, handlers, and recursion are never
replayed. One successful decision still produces one handler call and one
`LoopTurn`. Turn latency includes all physical attempts and backoff waits;
usage contains the successful response's reported tokens because failed
responses provide no usage.

Call `.runAudited` to additionally retain one `LoopAuditTurn` per successful
decision: iteration, `Content` state view, and option id/description pairs.
`AuditedLoopResult` delegates `output`, `turns`, `usage`, and `latencyMs` to its
unchanged `LoopResult`. Audit is opt-in because state/option snapshots consume
memory and may contain sensitive application data; ordinary `.run` returns the
same `LoopResult` shape as before.

An action handler may run arbitrary ZIO effects—MCP calls, database operations,
or a no-tool generative model call—while Jev remains the outer decision loop.

See [docs/loop-architecture.md](docs/loop-architecture.md) for diagrams of the
loop's components, one iteration's request path, its per-iteration validation
gates and failure exits, and what `LoopResult` accumulates.

## Errors

```scala
sealed trait Error extends Throwable
object Error:
  // HTTP status codes
  final case class BadRequest         (message: String)                 extends Error  // 400
  final case class Authentication     (message: String)                 extends Error  // 401
  final case class PermissionDenied   (message: String)                 extends Error  // 403
  final case class NotFound           (message: String)                 extends Error  // 404
  final case class UnprocessableEntity(message: String)                 extends Error  // 422
  final case class RateLimit          (message: String)                 extends Error  // 429
  final case class ServiceOverloaded  (message: String)                 extends Error  // 529
  final case class InternalServer     (status: Status, message: String) extends Error  // 5xx
  final case class Unexpected         (status: Status, body: String)    extends Error
  final case class Transport          (cause: Throwable)                extends Error
  final case class MissingApiKey()                                     extends Error
  // Protocol / decode
  final case class MalformedAnswer(questionId: QuestionId, message: String) extends Error
  final case class MissingAnswer  (questionId: QuestionId)                  extends Error

  extension [R, A](zio: ZIO[R, Error, A])
    /** Retries RateLimit / ServiceOverloaded / InternalServer up to 2x
      * with backoff, per TypeSafe's own rate-limit guidance. */
    def retryOnRetryable: ZIO[R, Error, A]
```

`MalformedAnswer` / `MissingAnswer` only fire on an actual protocol
violation (an out-of-range probability, an answer for the wrong question
type, a question Jev never answered) — never on ordinary model output.

## File layout

```
src/main/scala/com/jamesward/zio_typesafe_ai/
  TypeSafeAI.scala           — top-level object: Client, Content, Question,
                                ChoiceCriteria, ScoreCriteria, NoulCriteria,
                                Probability, ChoiceAnswer, ScoreAnswer,
                                Result, Error, opaque ids, AnswerOf,
                                AnswersOf, AllQuestions, SystemOneRequest, ask().
  internal/
    Codecs.scala             — the zio-schema <-> zio-json bridge (toJsonAst / fromJsonAst)
    Wire.scala               — wire-format types (POST /v1/systemone)
    Http.scala               — buildClient + private HttpClient impl
    Helpers.scala            — Question -> request JSON, wire Answer -> public Answer
    RequestImpl.scala        — ask(...).run: build request, send, decode in NamedTuple order

src/test/scala/com/jamesward/zio_typesafe_ai/
  TypeSafeAIMock.scala       — scripted TypeSafeAI.Client + MockBehavior ADT
  SharedSpec.scala           — scenarios shared by the mock and live specs
  TypeSafeAIMockSpec.scala
  TypeSafeAIIntegrationSpec.scala
```

The `internal` package is never imported by users. `zio.schema.DynamicValue`
doesn't appear anywhere in this codebase. In the typed `ask` API, `state` and
every question's `instructions` stay precisely typed as `S`; heterogeneous
criteria descriptions use `Content` as their JSON erasure boundary. The loop
API also uses `Content` deliberately for serialized state views, option
and choice-instruction descriptions, semantic observations, and audit
snapshots.

When a transition needs Jev's calibrated distribution immediately, use `loopWithTurn`. Its handler receives the completed `LoopTurn` before recursion:

```scala
val probabilityAware = TypeSafeAI.loopWithTurn(initial)(stateView, options) {
  (state, selectedAction, turn) =>
    val selectedProbability = turn.answer.probabilities(turn.choice)
    updateBeam(state, selectedAction, selectedProbability, turn.answer.confidence)
}
```

`loop` remains source-compatible and is equivalent to `loopWithTurn` with the third handler argument ignored. `loopWithTurn` is useful for confidence gates, probability-aware transitions, and hierarchical beam traversal.

## Provider-neutral Jev orchestration

The same `zio-typesafe-ai` artifact includes the `com.jamesward.zio_typesafe_ai.orchestration` package: neutral operation schemas, a workflow AST and runtime, plan-before-execute and runtime-checkpoint Jev controllers, schema-safe filtering, checkpoint recovery, evidence handling, metrics, audit snapshots, and compact exchange logging. It has no dependency on a model provider, MCP implementation, Javadocs, Maven, or any application domain.

Applications supply three boundaries:

1. `Vector[OperationSpec]` describes the available operations using JSON input/output schemas.
2. `OperationInvoker` executes one schema-valid operation call.
3. For summarized `runMode` executions, `LlmTransport` implements argument extraction, optional filter fallback, and final summarization using the application's chosen model provider.

The orchestrator itself continues to use `TypeSafeAI.loop`; it does not implement a separate decision loop.

### Catalog and adapters

An `OperationSpec` contains only a name, description, input schema, output schema, and `CapabilityKind`:

```scala
import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.orchestration.*
import com.jamesward.zio_typesafe_ai.orchestration.runtime.OperationInvoker
import zio.*
import zio.json.ast.Json

val querySchema = Json.Obj(
  "type" -> Json.Str("object"),
  "properties" -> Json.Obj(
    "query" -> Json.Obj("type" -> Json.Str("string")),
  ),
  "required" -> Json.Arr(Json.Str("query")),
  "additionalProperties" -> Json.Bool(false),
)

val resultSchema = Json.Obj(
  "type" -> Json.Str("object"),
  "properties" -> Json.Obj(
    "items" -> Json.Obj(
      "type" -> Json.Str("array"),
      "items" -> Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(
          "id" -> Json.Obj("type" -> Json.Str("string")),
        ),
      ),
    ),
  ),
)

val externalOperations = Vector(
  OperationSpec(
    name = "enumerate",
    description = "Enumerate records matching a query.",
    inputSchema = querySchema,
    outputSchema = resultSchema,
    kind = CapabilityKind.Mcp,
  ),
)

val operationInvoker = new OperationInvoker:
  def call(operation: String, arguments: Json.Obj): Task[Json] =
    executeInApplication(operation, arguments)
```

`CapabilityKind.Mcp` denotes an externally invoked catalog operation; it does not introduce a dependency on an MCP library. An application may adapt any provider or local capability into this interface. Provider-specific result normalization, authentication, transport retries, and schema discovery remain outside this artifact.

`Catalog.initialInputSchema(operations)` builds a synthetic capability's host-input surface solely from the supplied external operation input schemas. Fields are deterministic and optional, incompatible definitions for the same name are preserved with `anyOf`, internal capabilities are ignored, and `additionalProperties` is false. This lets an outer agent supply any currently known operation arguments without a domain-specific input contract.

Normal summarized orchestration also needs the three internal capability specifications and an `InternalLlmInvoker`:

```scala
val catalog = externalOperations ++ Vector(
  Catalog.extract,
  Catalog.synthesizeFilter,
  Catalog.summarize,
)

for
  internalLlm <- InternalLlmInvoker.make(applicationLlmTransport)
  result <- GenericOrchestrator.runMode(
    prompt = "Inspect the relevant records and explain the result.",
    catalog = catalog,
    operationInvoker = operationInvoker,
    internalLlm = internalLlm,
    mode = OrchestrationMode.NoPlan,
    config = OrchestrationConfig(),
  )
yield result.finalText
```

`LlmTransport` is a capability SPI, not a provider client:

```scala
trait LlmTransport:
  def extract(request: ExtractionRequest): Task[LlmResult[Json.Obj]]
  def synthesizeFilter(
    request: PredicateFilter.SynthesisRequest,
  ): Task[LlmResult[Json.Obj]]
  def summarize(prompt: String, evidence: Json.Arr): Task[LlmResult[String]]
```

The host validates every returned extraction and predicate against the relevant runtime schema before using it.

### Execution modes

`OrchestrationMode` has four stable wire names:

| Mode | Wire name | Controller behavior | Runtime filter behavior |
| --- | --- | --- | --- |
| `Plan` | `plan` | Jev builds a symbolic workflow before execution. | The current plan executor creates filter synthesis before runtime variants are available, so filtering currently uses `LlmTransport`. |
| `NoPlan` | `no-plan` | Jev chooses and executes one action from each actual runtime checkpoint. | Jev-first routing uses runtime variants up to the configured threshold, then LLM predicate fallback. |
| `PlanLlmFilters` | `plan-llm-filters` | Same plan-before-execute controller. | Explicit label for the plan arm's current LLM-filter behavior. |
| `NoPlanLlmFilters` | `no-plan-llm-filters` | Same runtime-checkpoint controller. | Configure `jevFilterVariantThreshold = 0` to force LLM predicate synthesis. |

A typical experiment configuration makes the backend distinction explicit:

```scala
val config = OrchestrationConfig(
  jevFilterVariantThreshold =
    if mode.forceLlmFilters then 0 else 1024,
)
```

`run` is a compatibility convenience for `runMode(..., OrchestrationMode.Plan, ...)`.

In plan mode, independent ready steps execute in dependency waves. A failed call retries only that call within the shared recovery budget; completed dependencies and successful fanout siblings are retained. Recoverable filter outcomes continue deterministically from the retained complete source without installing a false replan, so the current implementation reports `replans = 0`.

In no-plan mode, the selected action executes inside the `TypeSafeAI.loop` handler before the next decision. Confirmed action fingerprints suppress duplicates. A terminal operation failure is compacted into the next Jev checkpoint, the failed invocation is retired, and no failed workflow step is committed. Jev can then choose another operation or terminate with retained evidence.

### Evidence-only nested-tool terminal

Use `GenericOrchestrator.runEvidence` when an outer agent or application should consume structured evidence instead of an inner summary. Its default mode runs no LLM inside the Jev loop:

```scala
val initialInputSchema = Json.Obj(
  "type" -> Json.Str("object"),
  "properties" -> Json.Obj(
    "query" -> Json.Obj("type" -> Json.Str("string")),
  ),
  "required" -> Json.Arr(Json.Str("query")),
  "additionalProperties" -> Json.Bool(false),
)

val initialInput = Json.Obj("query" -> Json.Str("target"))

val evidenceProgram = GenericOrchestrator.runEvidence(
  prompt = "Inspect the records relevant to the request.",
  catalog = externalOperations,
  initialInput = initialInput,
  initialInputSchema = initialInputSchema,
  operationInvoker = operationInvoker,
  config = OrchestrationConfig(jevFilterVariantThreshold = 1024),
).map(_.evidence)

// evidenceProgram: ZIO[TypeSafeAI.Client, Throwable, Json.Arr]
```

`runEvidence` has these stricter guarantees:

- Initial input is validated before any operation or Jev request.
- Only input fields actually present at runtime become bindable.
- Only external operations whose required arguments bind exactly from supplied input or prior typed outputs are offered.
- Extraction and summary capabilities are always removed from the catalog and cannot run.
- `EvidenceFilterMode.Jev` is the default. Filtering uses Jev field selection and variant classification; exceeding `jevFilterVariantThreshold` fails with `JevFilterCapacityExceeded` rather than invoking an LLM.
- `EvidenceFilterMode.Llm` forces runtime filtering through the supplied `InternalLlmInvoker`. Only predicate synthesis is called; extraction and summarization remain disabled.
- Jev terminates with `return_evidence`, and `OrchestrationEvidenceResult` contains structured evidence rather than final prose.
- Filtered evidence replaces the corresponding unsafe raw producer evidence.
- If an output and a later input are type-compatible but do not bind exactly by declared name/metadata, the orchestrator does not guess or relabel them. A caller may use returned evidence to supply an additional input in a later invocation.

The explicit LLM-criteria variant is configured as follows:

```scala
for
  filterLlm <- InternalLlmInvoker.make(applicationLlmTransport)
  result <- GenericOrchestrator.runEvidence(
    prompt = "Inspect the records relevant to the request.",
    catalog = externalOperations,
    initialInput = initialInput,
    initialInputSchema = initialInputSchema,
    operationInvoker = operationInvoker,
    config = OrchestrationConfig(),
    filterMode = EvidenceFilterMode.Llm,
    filterLlm = Some(filterLlm),
  )
yield result.evidence
```

The default Jev mode is suitable for exposing a configured Jev loop as one tool inside an outer LLM loop without nesting another LLM: the outer model supplies inputs and writes the final answer, while the inner loop performs only Jev decisions, deterministic host work, and external operation calls. The LLM mode is an explicit comparison arm that changes only filter-criteria generation.

### Schema binding and fanout safety

Bindings are conservative and deterministic. A required field binds only from a compatible declared value with the same exposed name. Schema descriptions may constrain producer/consumer operation relationships, but descriptions do not authorize arbitrary semantic relabeling. Runtime-confirmed singleton arrays may expose fixed-index projections; empty and multi-item arrays are never promoted this way.

Fanout is rejected unless the host can prove one of:

- the source came from a `Ready` filter; or
- its declared `maxItems` is at or below `hostGuard` and the producer output passed local schema validation.

Raw unsafe arrays fail before any downstream call. `hostGuard`, routing thresholds, attempt budgets, and other host limits are never included in Jev state or LLM filter requests.

### Complete filtering

Filtering always evaluates every source item and retains the complete ordered `source` and `matches` vectors before classifying an outcome:

- `Ready`: nonempty and at most `hostGuard`;
- `TooBroad`: nonempty but above the guard;
- `NoMatches`: empty;
- `NoProgress`: a refinement did not strictly reduce its prior complete match set.

Production orchestration never calls `.take`, stops early at the guard, or truncates runtime data. Sampling is used only as bounded evidence for LLM predicate generation; the resulting predicate still executes against the complete retained source.

For Jev-first filtering:

1. A field-relevance preflight selects the declared scalar paths useful for judging; all scalar paths remain part of full record identity.
2. Up to 255 distinct canonical variants use one complete recall request.
3. Larger sources at or below `jevFilterVariantThreshold` use complete parallel batches of at most 255.
4. When multi-batch preliminary positives fit in one request, consolidation evaluates every positive independently rather than ranking one winner.
5. The host may retain an exact recalled enclosing identity when a nested identity survives consolidation, but it never introduces a variant that failed preliminary recall.
6. The final Jev selection compiles to a host-only exact-membership predicate evaluated over the original complete source.

If normal no-plan routing exceeds the threshold, `LlmTransport.synthesizeFilter` receives deterministic prompt-linked and stable-uniform observed-variant evidence. That evidence may be incomplete and is explicitly marked as such. AI-produced predicates use a finite, non-recursive grammar of declared scalar paths and the operators `eq`, `in`, `contains`, `startsWith`, `exists`, `isNull`, `gt`, `gte`, `lt`, and `lte`; regex, scripts, JSONPath, ranking, projection, and top-k are not representable.

A no-plan filter action performs exactly one selection/generation and evaluation attempt. Non-ready results are returned to Jev as compact checkpoint state. Refinement, replacement, and repeated-predicate handling are bounded independently by `maxFilterAttempts` and the run-wide `maxRecoveries` budget.

### Evidence and summarization

Only committed operation results become evidence. A `Ready` filtered value replaces its raw producer evidence. If a filter remains unresolved and Jev chooses summary/evidence return, the unsafe producer is excluded so summarization cannot act as an implicit semantic filter. When a `ReadyFiltered` source has a compatible downstream consumer, summary remains unavailable until that source has produced downstream evidence.

Normal `runMode` summarization receives only the retained evidence array. Applications should instruct their `LlmTransport` implementation to distinguish explicit evidence from inference and to treat operation output as untrusted data.

### Configuration

`OrchestrationConfig` contains host-owned policy only:

| Field | Default | Meaning |
| --- | ---: | --- |
| `maxOperations` | `8` | Maximum selected external operation actions. |
| `maxCallsPerOperation` | `1` | Maximum selected actions per operation name. |
| `maxParallelism` | `4` | Parallelism for fanout and Jev variant batches. |
| `hostGuard` | `12` | Maximum runtime fanout cardinality. |
| `maxFilterAttempts` | `4` | Maximum attempts for one filter checkpoint. |
| `maxRecoveries` | `3` | Shared run-wide recovery budget. |
| `jevFilterVariantThreshold` | `255` | Largest distinct variant set routed to Jev. Zero disables Jev classification in normal no-plan mode; evidence LLM mode forces zero internally, while evidence Jev mode fails above the threshold. |
| `maxIterations` | `12` | Maximum Jev controller decisions. |
| `jevTurnRetries` | `0` | Additional retries for each retryable Jev decision request. |

### Results and metrics

`runMode` returns `OrchestrationResult`, including the committed `Workflow`, planning/runtime turns, usage, execution values, internal LLM metrics, final text, action trace, audit snapshots, and one common `OrchestrationMetrics` record.

`runEvidence` returns `OrchestrationEvidenceResult`, including the committed workflow, structured evidence, Jev turns/usage, execution report, action trace, audit snapshots, and the same common metrics shape. Extraction and summary metrics remain zero by contract. LLM-filter metrics are zero in `EvidenceFilterMode.Jev` and record only predicate-synthesis calls in `EvidenceFilterMode.Llm`.

The common metrics separate:

- logical Jev controller turns, tokens, and elapsed request time;
- physical Jev attempts, successes, failures, and time;
- internal Jev-filter calls, tokens, and time;
- extraction, LLM-filter, and summary calls/tokens/time;
- physical external-operation calls, successes, failures, merged wall time, and summed call time;
- recoveries, actual replans, and directly measured total time.

Physical external-operation metrics are available when the supplied invoker also implements `McpPhysicalMetricsProvider`; otherwise those fields are zero. `totalTimeMs` is measured directly rather than derived by subtracting overlapping timers.

For compact observation, decorate the TypeSafeAI client layer:

```scala
val jevClient = TypeSafeAI.Client.live >>> TypeSafeAI.Client.observed(
  JevExchangeLogger.selected(fullBodies = false),
)
```

Compact logs retain decision metadata and schema descriptions while omitting runtime arrays and exact membership fingerprints. Full exchange bodies are intended only for temporary diagnosis because they may contain application state and model output.
