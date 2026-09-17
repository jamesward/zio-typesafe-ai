# zio-typesafe-ai

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
doesn't appear anywhere in this codebase — `state` and every question's
`instructions` stay precisely typed as `S` all the way through; only
criteria descriptions (several, possibly differently typed, inside one
`ChoiceCriteria` / `ScoreCriteria` / `NoulCriteria`) need an erasure
boundary, and that's `Content`, wrapping `zio.json.ast.Json`.
