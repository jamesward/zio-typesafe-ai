# zio-typesafe-ai

A Scala 3 / ZIO library for [TypeSafe AI](https://docs.typesafe.ai/introduction)'s
Jev / System One API: `POST https://api.typesafe.ai/v1/systemone`.

## Goals

1. **Typed, atomic questions.** Jev has exactly three question shapes —
   `Question.Noul` ("is this true?"), `Question.Choice` ("which of
   these?"), `Question.Score` ("which level?") — modeled as a closed
   `sealed trait Question[S]` with one generic class per shape, `S`
   being the type of that question's `instructions`.
2. **NamedTuple-driven request/response symmetry.** `ask(state, questions)`
   takes a `NamedTuple` of `Question`s; `.answers` / `.run` return a
   `NamedTuple` with the *same field names*, each typed per question
   (`AnswerOf[Question.Noul[?]] = Probability`, `AnswerOf[Question.Choice[?]]
   = ChoiceAnswer`, `AnswerOf[Question.Score[?]] = ScoreAnswer`) via the
   `AnswerOf` / `AnswersOf` match types. No stringly-typed answer-map
   lookups anywhere in the public API.
3. **Illegal states unrepresentable.**
   - `ChoiceCriteria` (1–255 options) and `ScoreCriteria` (2–10 levels)
     are built only through smart constructors returning
     `Either[String, _]` — an out-of-range criteria set cannot be
     constructed, let alone sent on the wire.
   - `Probability` is an opaque `Double` provably within `[0.0, 1.0]`;
     every `noul`, `confidence`, and probability-map value the service
     returns is one. A value outside that range can't reach it — decoding
     one fails closed as `Error.MalformedAnswer`, never silently clamped.
4. **Typed `ask` state and instructions are not wrapped.** `ask` and every
   `Question` factory are generic in a `Schema` type parameter directly
   (`ask[S: Schema](state: S, ...)`, `Question.Noul[S: Schema](instructions:
   S, ...)`) and keep `S` as a real type parameter on the built value —
   `question.instructions: S` reads back exactly what you passed in, no
   decode step, no `Either`. Within typed `ask`, only heterogeneous criteria
   descriptions need the `Content` erasure boundary. The loop API additionally
   uses `Content` for serialized state views, options, configurable choice
   instructions, observations, and audit snapshots.
5. **No `DynamicValue`.** `state` / `instructions` / criteria
   descriptions are genuinely heterogeneous (any `Schema`-having type,
   varying per call and per question), so *some* "already a JSON value"
   representation is required wherever they're combined — but it's
   `zio.json.ast.Json`, not `zio.schema.DynamicValue`. See "Why
   `zio.json.ast.Json`, not `DynamicValue`" below.
6. **Builder/service split.** `ask(...)` returns a pure-data
   `SystemOneRequest[Names, Values]`. `.answers` / `.run` require
   `TypeSafeAI.Client` in the env; `Client` is the only thing that
   touches the wire, provided as a `ZLayer` and backed by a `trait` so
   test mocks plug in directly.
7. **Errors are explicit.** Every documented HTTP status
   (`docs.typesafe.ai/api`) has its own `Error` case; protocol violations
   (`MissingAnswer`, `MalformedAnswer`) are distinct from transport/HTTP
   failures.
8. **Mockable.** `TypeSafeAIMock(behaviors*)` (in `src/test`) provides a
   scripted, deterministic `Client` for unit tests. One `Respond`/`Fail`
   behavior scripts one whole `ask(...).run` call — System One answers
   every registered question in a single round-trip, unlike a multi-turn
   chat API.

## Non-goals

- The optional `GET /v1/models` model-listing endpoint the SDKs expose.
- Any request-level sampling/inference controls — System One has none;
  it's a structured-decision endpoint, not a text-generation one.
- Streaming — the endpoint is a single JSON request/response, no SSE
  variant is documented.

## Why `Question` is a `sealed trait`, not an `enum`

Scala 3 widens an `enum` case's inferred type to the enum type itself
wherever it isn't otherwise constrained — including a bare `NamedTuple`
field, which is exactly the position `ask`'s `Values` type parameter is
inferred from. That widening would collapse every question to plain
`Question[?]` and leave `AnswerOf` unable to pick a branch. A `sealed
trait` + plain classes carries its own precise type at that call site
(`ask(..., (isUrgent = Question.Noul("...")))` infers that field as
`Question.Noul[String]`), with identical call-site syntax
(`Question.Noul(...)` still works via the companion object) and
identical exhaustive-match support.

## Why `Question.Noul` / `Choice` / `Score` are generic in `S`

`instructions` is required to have *some* `Schema` — any type works, not
just `String` — so the natural signature is `Question.Noul[S: Schema]
(instructions: S, ...)`. Keeping `S` as the class's own type parameter
(rather than converting to an erased `Content` at construction) means
`question.instructions: S` is the exact value the caller passed, with no
decode step and no possible failure — there's nothing to get wrong,
because nothing was thrown away. The captured `Schema[S]` rides along as
a private field so `Helpers` can still encode `instructions` to JSON
later, at the one point that actually needs a JSON representation
(building the wire request) — never earlier, and never exposed on the
type itself.

This works because Scala recovers the `S` ↔ `Schema[S]` pairing through
an ordinary pattern match: `case n: Question.Noul[s] => ...` binds a
fresh existential type variable `s`, and both `n.instructions: s` and
`n.schema: Schema[s]` refer to the *same* `s` inside that branch, so
`Codecs.toJsonAst(n.instructions)(using n.schema)` type-checks soundly
with no cast.

## Why `zio.json.ast.Json`, not `DynamicValue`

`zio.schema.DynamicValue` (annotated `@directDynamicMapping`) is
`zio-schema`'s own built-in mechanism for "opaque, already-JSON-shaped
passthrough" inside a `derives Schema` case class — it exists
specifically for this scenario. Using it would have meant keeping the
whole request (`state`, `model`, `questions`) as one derived-`Schema`
value, the way `zio-bedrock-converse`'s wire types are.

This library doesn't need that, because — unlike the response, which
stays a plain `derives Schema` case class end to end (`Answer` / `Usage`
/ `SystemOneResponse`; nothing about it is dynamic) — the *request*'s
dynamic fields are always encoded from a concretely-known `Schema[S]` at
the exact point of construction. So instead of merging everything into
one derived `Schema[SystemOneRequest]`, `Helpers` encodes `state` and
each question's `instructions` **independently**, each via its own
`Schema[S]` (`Codecs.toJsonAst`, which round-trips through
`zio-schema-json`'s binary codec and `zio-json`'s own parser), and
composes the pieces with `zio-json`'s own `Json.Obj` / `Json.Arr`
constructors. `Http` renders the final tree with `zio-json`'s own
printer (`.toJson`) — real, tested code from both libraries, no
hand-rolled JSON string escaping anywhere.

The result: `zio.schema.DynamicValue` does not appear anywhere in this
codebase. `Content` wraps `zio.json.ast.Json` wherever a stable serialized
erasure boundary is needed: heterogeneous criteria descriptions in typed
questions, and loop state views, option/choice instructions, observations,
and audit snapshots.

## Authentication & configuration

| Env var                    | Used by `live` | Required | Default      |
| --------------------------- | -------------- | -------- | ------------ |
| `TYPESAFE_API_KEY`          | yes            | yes      | —            |
| `TYPESAFE_DEFAULT_MODEL`    | yes            | no       | `jev-latest` |

These match the official Python/JavaScript SDKs' env var names. Endpoint:
`https://api.typesafe.ai/v1/systemone`, bearer-token authenticated.

## Public API surface

```scala
package com.jamesward.zio_typesafe_ai

object TypeSafeAI:

  // Opaque IDs
  opaque type ApiKey     = String
  opaque type ModelId    = String       // ModelId.JevLatest = ModelId("jev-latest")
  opaque type QuestionId = String       // NamedTuple key ↔ wire question id ↔ answer key

  opaque type Probability = Double      // smart-constructed, always in [0.0, 1.0]
  object Probability:
    def apply(value: Double): Either[String, Probability]   // private[zio_typesafe_ai]

  /** Wraps a zio-json `Json` node — never `DynamicValue`. In typed `ask`
    * requests it erases heterogeneous criteria descriptions; loops also use
    * it for serialized state views, option/choice instructions, observations,
    * and audit snapshots. */
  final class Content private (json: zio.json.ast.Json):
    def as[A: Schema]: Either[String, A]
  object Content:
    def apply[A: Schema](value: A): Content

  final class NoulCriteria private (val whenTrue: Content | Null, val whenFalse: Content | Null)
  object NoulCriteria:
    def apply(whenTrue: String | Null = null, whenFalse: String | Null = null): NoulCriteria
    def fromContent(whenTrue: Content | Null = null, whenFalse: Content | Null = null): NoulCriteria

  final class ChoiceCriteria private (val options: Map[String, Content | Null]):
    def size: Int
  object ChoiceCriteria:                        // 1..255 options
    def apply(options: (String, String | Null)*): Either[String, ChoiceCriteria]
    def fromContent(options: Map[String, Content | Null]): Either[String, ChoiceCriteria]

  final class ScoreCriteria private (val levels: List[Content | Null]):
    def size: Int
  object ScoreCriteria:                         // 2..10 levels
    def apply(levels: (String | Null)*): Either[String, ScoreCriteria]
    def fromContent(levels: List[Content | Null]): Either[String, ScoreCriteria]

  sealed trait Question[S]
  object Question:
    final class Noul[S] private (val instructions: S, val criteria: NoulCriteria | Null, schema: Schema[S]) extends Question[S]
    object Noul:
      def apply[S: Schema](instructions: S, criteria: NoulCriteria | Null = null): Noul[S]

    final class Choice[S] private (val instructions: S, val criteria: ChoiceCriteria, schema: Schema[S]) extends Question[S]
    object Choice:
      def apply[S: Schema](instructions: S, criteria: ChoiceCriteria): Choice[S]

    final class Score[S] private (val instructions: S, val criteria: ScoreCriteria, schema: Schema[S]) extends Question[S]
    object Score:
      def apply[S: Schema](instructions: S, criteria: ScoreCriteria): Score[S]

  case class ChoiceAnswer(choice: String, probabilities: Map[String, Probability], confidence: Probability)
  case class ScoreAnswer(score: Double, legend: Map[Int, String], probabilities: Map[Int, Probability], confidence: Probability)

  type AnswerOf[Q] <: Matchable = Q match
    case Question.Noul[?]   => Probability
    case Question.Choice[?] => ChoiceAnswer
    case Question.Score[?]  => ScoreAnswer

  type AnswersOf[Hs <: Tuple] <: Tuple = Hs match
    case h *: rest  => AnswerOf[h] *: AnswersOf[rest]
    case EmptyTuple => EmptyTuple

  sealed trait AllQuestions[Hs <: Tuple]        // inductive evidence, mirrors AllTools
  object AllQuestions:
    given empty: AllQuestions[EmptyTuple]
    given consNoul  [S, Tail <: Tuple](using AllQuestions[Tail]): AllQuestions[Question.Noul[S]   *: Tail]
    given consChoice[S, Tail <: Tuple](using AllQuestions[Tail]): AllQuestions[Question.Choice[S] *: Tail]
    given consScore [S, Tail <: Tuple](using AllQuestions[Tail]): AllQuestions[Question.Score[S]  *: Tail]

  case class Usage(inputTokens: Int, outputTokens: Int)
  case class Result[+T](answers: T, model: ModelId, usage: Usage)

  trait Client:
    def modelId: ModelId
    private[zio_typesafe_ai] def send(req: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse]
  enum ExchangeOutcome:
    case Success(response: Json)
    case Failure(cause: Cause[Error])
  case class ExchangeObservation(request: Json, outcome: ExchangeOutcome, latencyMs: Long)
  trait ExchangeObserver:
    def observe(observation: ExchangeObservation): UIO[Unit]
  object ExchangeObserver:
    val none: ExchangeObserver
    def fromFunction(f: ExchangeObservation => UIO[Unit]): ExchangeObserver
    val logging: ExchangeObserver

  object Client:
    def observed(observer: ExchangeObserver): URLayer[Client, Client]
    def layer(apiKey: ApiKey, modelId: ModelId = ModelId.JevLatest): ZLayer[HClient, Nothing, Client]
    val  live:                                                       ZLayer[HClient, Error, Client]

  /** Two type params, not one `NT <: NamedTuple.AnyNamedTuple`: with
    * `Names` / `Values` both in scope, `questions.toTuple` (the
    * stdlib's own compiler-checked unwrap) resolves directly — an
    * abstract `NT` alone can't decompose that way, only via the
    * `NamedTuple.Names[NT]` / `DropNames[NT]` match-type projections,
    * which don't carry enough structure for the extension method to
    * apply and would force an unchecked `questions.asInstanceOf[Tuple]`. */
  final class SystemOneRequest[Names <: Tuple, Values <: Tuple] private (
    state: Content, model: Option[ModelId], entries: List[(QuestionId, Question[?])],
  ):
    def model(m: ModelId): SystemOneRequest[Names, Values]
    def answers: ZIO[Client, Error, NamedTuple.NamedTuple[Names, AnswersOf[Values]]]
    def run:     ZIO[Client, Error, Result[NamedTuple.NamedTuple[Names, AnswersOf[Values]]]]

  inline def ask[S: Schema, Names <: Tuple, Values <: Tuple](
    state: S, questions: NamedTuple.NamedTuple[Names, Values],
  )(using
    inline ev:  Values <:< NonEmptyTuple,
    inline all: AllQuestions[Values],
  ): SystemOneRequest[Names, Values]
```

## Loop API additions

`loop` and `loopWithTurn` retain their existing signatures and descriptors. Their builder adds copy-style `.choiceInstructions[I: Schema](value)`, `.retryEachTurn(LoopRetryPolicy)`, `.observe(LoopObserver[E, O])`, and `.runAudited`; `.model`, `.maxIterations`, and all new methods preserve every other setting.

```scala
case class LoopRetryPolicy(maxRetries: Int, initialDelay: Duration = 500.millis)
case class LoopAuditOption(id: String, description: Content)
case class LoopAuditTurn(iteration: Int, stateView: Content, options: List[LoopAuditOption])
case class AuditedLoopResult[+O](result: LoopResult[O], audit: List[LoopAuditTurn]):
  def output: O
  def turns: List[LoopTurn]
  def usage: Usage
  def latencyMs: Long

enum LoopObservation[+E, +O]:
  case OptionsGenerated(iteration: Int, stateView: Content, options: List[LoopAuditOption])
  case Decision(turn: LoopTurn)
  case Completion(result: LoopResult[O])
  case Failure(cause: Cause[Error | E], turns: List[LoopTurn])

trait LoopObserver[E, O]:
  def observe(observation: LoopObservation[E, O]): UIO[Unit]
object LoopObserver:
  def none[E, O]: LoopObserver[E, O]
  def make[E, O](f: LoopObservation[E, O] => UIO[Unit]): LoopObserver[E, O]
```

Observers are best-effort and cannot replace client/loop semantics with callback defects or interruption. Exchange events contain no headers/tokens but do contain potentially sensitive full bodies. Successful exchange response JSON is the complete decoded `SystemOneResponse` canonically re-encoded, not byte-exact HTTP response bytes.

Semantic loop ordering is validated options → options event → Jev → decision event → handler → recursion/completion. One outer terminal failure event carries the accumulated turns and `Cause[Error | E]`. Per-turn retries apply only to the dynamic Jev request and only for `RateLimit`, `ServiceOverloaded`, and `InternalServer`; default is zero retries. Retry latency includes attempts/backoff, while usage is available only from the successful response. Audit is opt-in, retains only `Content` state plus option id/description snapshots, and grows with turns/options; ordinary `run` retains and returns the unchanged `LoopResult`.

## Errors

```scala
sealed trait Error extends Throwable
object Error:
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
  final case class MalformedAnswer(questionId: QuestionId, message: String) extends Error
  final case class MissingAnswer  (questionId: QuestionId)                  extends Error

  extension [R, A](zio: ZIO[R, Error, A])
    def retryOnRetryable: ZIO[R, Error, A]   // RateLimit / ServiceOverloaded / InternalServer, 2x backoff
```

## Dispatch model

```
┌─ ask(state, namedTupleOfQuestions).answers / .run ───────────────────────┐
│                                                                           │
│  build request body (Helpers.toWireRequest, a zio.json.ast.Json tree):   │
│    "state"     = state.json                    (Content, built at ask() │
│                                                  time via Codecs.toJsonAst)│
│    "model"     = request override, else Client.modelId                  │
│    "questions" = { id -> questionJson(question) } per entry, where       │
│                   questionJson encodes `instructions` via the            │
│                   Schema[S] captured on that very question               │
│                                                                           │
│  Http renders the tree with zio-json's own printer and sends it          │
│  (one round-trip; System One answers every registered question at        │
│  once — no multi-turn dispatch)                                          │
│                                                                           │
│  response decodes normally via a derived Schema[SystemOneResponse]       │
│  (fully fixed-shape — no Json/DynamicValue on this side at all)          │
│                                                                           │
│  for each (id, question) in NT order:                                    │
│    lookup id in wire.answers                                             │
│      missing            → fail Error.MissingAnswer                       │
│    match (question, answer):                                             │
│      (Noul, Noul(v))                 → Probability(v)                    │
│      (Choice, Choice(c, probs, cf))  → ChoiceAnswer(c, probs', cf')       │
│      (Score, Score(s, lg, probs, cf))→ ScoreAnswer(s, lg', probs', cf')   │
│      _ (type mismatch)               → fail Error.MalformedAnswer        │
│      any Probability(_) out of range → fail Error.MalformedAnswer        │
│                                                                           │
│  assemble the decoded answers into a Tuple in NT order; RequestImpl.run,  │
│  generic in the same Names/Values SystemOneRequest carries, casts it     │
│  once to the NamedTuple `.answers` / `.run` declares                     │
└───────────────────────────────────────────────────────────────────────────┘
```

## Mock layer

Lives in `src/test/scala/.../TypeSafeAIMock.scala` — not in the
production jar.

```scala
object TypeSafeAIMock:
  sealed trait MockBehavior
  object MockBehavior:
    case class Respond(answers: Map[String, Wire.Answer], usage: Wire.Usage = Wire.Usage(0, 0)) extends MockBehavior
    case class Fail(error: TypeSafeAI.Error) extends MockBehavior

  def apply(behaviors: MockBehavior*): ULayer[TypeSafeAI.Client]
  def tracked(behaviors: MockBehavior*): UIO[Tracked] // layer + requests/requestCount
```

One behavior scripts one `send` — i.e. one whole `ask(...).run` call,
since System One has no multi-turn concept. `tracked` records every assembled
request (including retry attempts) without changing scripted behavior.

## Test layout

`SharedSpec` declares two scenario lists, run against both the mock and
the live model where the scenario's assertions are loose enough to
trust a live judgment call (a probability's *shape*, not its exact
value):

- **`happyPathScenarios`** — Noul / Choice / Score / combined, asserted
  on shape (`Probability` in range, `choice` is a registered option,
  `score` in `[0, levels-1]`) plus one fact obvious enough for a live
  model to get right on an easy prompt.
- **`mockOnlyScenarios`** — exact-value decode checks and every error
  path (`MissingAnswer`, `MalformedAnswer` for an out-of-range value and
  for a type mismatch, and a scripted service failure) — responses no
  real model produces on purpose.

```
TypeSafeAIMockSpec        — criteria-constructor unit tests + all scenarios   vs mock
TypeSafeAIIntegrationSpec — happyPathScenarios only                          vs live (gated on TYPESAFE_AI_KEY)
```

```scala
trait TypeSafeAIScenario:
  def name:       String
  def run:        ZIO[TypeSafeAI.Client, Any, TestResult]
  def mockScript: List[TypeSafeAIMock.MockBehavior]
```

## File layout

```
src/main/scala/com/jamesward/zio_typesafe_ai/
  TypeSafeAI.scala           — top-level object (see "Public API surface")
  internal/
    Codecs.scala             — the zio-schema <-> zio-json bridge (toJsonAst / fromJsonAst),
                                codecConfig (response-side only now)
    Wire.scala               — SystemOneRequest (a Json tree, not derives Schema);
                                Answer / Usage / SystemOneResponse (derives Schema, unchanged)
    Http.scala               — buildClient + private HttpClient impl; renders the request
                                Json tree with zio-json's printer, decodes the response
                                with the derived Schema as before
    Helpers.scala            — Question -> Json (request assembly), Wire.Answer -> public Answer
    RequestImpl.scala        — ask(...).run: build request, send, decode in NamedTuple order

src/test/scala/com/jamesward/zio_typesafe_ai/
  TypeSafeAIMock.scala       — scripted Client + MockBehavior ADT
  SharedSpec.scala           — happyPathScenarios + mockOnlyScenarios
  TypeSafeAIMockSpec.scala
  TypeSafeAIIntegrationSpec.scala
```

## JSON encoding strategy

- **Response** (fully fixed-shape): `zio-schema-derivation` +
  `zio-schema-json`'s `JsonCodec.schemaBasedBinaryCodec`, exactly like
  `zio-bedrock-converse`'s wire types. `Answer` / `Question` shapes use
  `@discriminatorName("type")` + `@caseName(...)` — an internally-tagged,
  flat shape (`{"type": "noul", "noul": 0.93}`). `Usage`'s
  `input_tokens` / `output_tokens` use `@fieldName(...)`.
- **Request** (genuinely dynamic in `state` / `instructions` / criteria):
  never one derived `Schema`. Each dynamic value is encoded
  independently via its own `Schema[S]` to JSON bytes, parsed into a
  `zio.json.ast.Json` node with `zio-json`'s own parser
  (`Codecs.toJsonAst`), and composed into the final tree with
  `Json.Obj` / `Json.Arr`. `Http` renders the tree with `zio-json`'s own
  printer (`.toJson`). No hand-rolled JSON text or escaping anywhere in
  this library.
