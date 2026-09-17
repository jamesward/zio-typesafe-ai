package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.internal.{Codecs, Helpers, Http, RequestImpl, Wire}
import zio.*
import zio.direct.*
import zio.http.{Client as HClient, Status}
import zio.json.ast.Json
import zio.schema.Schema

/**
 * Top-level entrypoint for TypeSafe AI's Jev / System One API
 * (docs.typesafe.ai). Jev answers typed, atomic questions about a piece
 * of `state` instead of generating text:
 *
 *   - [[Question.Noul]]   — "is this true?" → a calibrated [[Probability]].
 *   - [[Question.Choice]] — "which of these?" → the selected option plus
 *     the full probability distribution.
 *   - [[Question.Score]]  — "which level?" → a probability-weighted mean
 *     over an ordered scale.
 *
 * Ask one or more questions at once with [[ask]]; the `NamedTuple` of
 * questions becomes the wire request *and* the compile-time shape of the
 * typed answer — see [[AnswerOf]].
 */
object TypeSafeAI:

  // ---------- Opaque domain types ----------

  opaque type ApiKey = String
  object ApiKey:
    def apply(s: String): ApiKey = s
    extension (k: ApiKey) def unwrap: String = k

  /** The model to target. Not a closed enum — TypeSafe may ship new
    * model ids — but [[ModelId.JevLatest]] covers the documented
    * default. */
  opaque type ModelId = String
  object ModelId:
    def apply(s: String): ModelId = s
    val JevLatest: ModelId = ModelId("jev-latest")
    extension (m: ModelId) def unwrap: String = m

  /** The `NamedTuple` key a [[Question]] is registered under in [[ask]].
    * Flows into the wire request's `questions` map key and back out as
    * the matching key in the response's `answers` map — the same
    * same-string-in-two-places pattern `ToolName` guards against in
    * `zio-bedrock-converse`. */
  opaque type QuestionId = String
  object QuestionId:
    def apply(s: String): QuestionId = s
    extension (q: QuestionId) def unwrap: String = q

  given CanEqual[ApiKey, ApiKey]         = CanEqual.derived
  given CanEqual[ModelId, ModelId]       = CanEqual.derived
  given CanEqual[QuestionId, QuestionId] = CanEqual.derived
  given CanEqual[Status, Status]         = CanEqual.derived

  /** A probability or confidence value, always within `[0.0, 1.0]`.
    * Every `noul`, `confidence`, and per-option/per-level probability
    * Jev returns is one of these — constructing one from an
    * out-of-range `Double` is impossible, so a protocol violation
    * surfaces as [[Error.MalformedAnswer]] at the decode boundary
    * instead of silently propagating a nonsense number. */
  opaque type Probability = Double
  object Probability:
    private[zio_typesafe_ai] def apply(value: Double): Either[String, Probability] =
      Either.cond(value >= 0.0 && value <= 1.0, value, s"probability out of range [0.0, 1.0]: $value")
    extension (p: Probability) def unwrap: Double = p
  given CanEqual[Probability, Probability] = CanEqual.derived

  /** Arbitrary JSON content — criteria descriptions accept a string, an
    * object, an array, or null, and multiple descriptions (possibly of
    * different origin types) live side by side in one [[ChoiceCriteria]]
    * / [[ScoreCriteria]], so something has to represent
    * "already-JSON-shaped value of statically unknown origin" once
    * they're combined. Wraps a `zio.json.ast.Json` node — never
    * `zio.schema.DynamicValue` — built from whatever `Schema`-having
    * value was passed, via `Codecs.toJsonAst`. See `Codecs` for why
    * `zio.json.ast.Json` rather than `DynamicValue`.
    *
    * `state` and every [[Question]]'s `instructions` don't need this:
    * they're generic in a `Schema` type parameter directly
    * (`ask[S: Schema](state: S, ...)`, `Question.Noul[S: Schema](...)`),
    * so reading them back is just the original, precisely-typed value —
    * no `Content`, no decode step. `Content` only shows up for criteria
    * descriptions, where [[ChoiceCriteria.apply]] / [[ScoreCriteria.apply]]
    * handle it for you, or via the `fromContent` escape hatches for
    * structured ones. */
  final class Content private[zio_typesafe_ai] (private[zio_typesafe_ai] val json: Json):
    def as[A: Schema]: Either[String, A] = Codecs.fromJsonAst(json)

  object Content:
    def apply[A: Schema](value: A): Content = new Content(Codecs.toJsonAst(value))

  // ---------- Questions ----------

  /** Clarifying `true`/`false` descriptions for a [[Question.Noul]].
    * Both sides are optional — omitting a description leaves that
    * outcome for Jev to interpret from the instructions alone. */
  final class NoulCriteria private (val whenTrue: Content | Null, val whenFalse: Content | Null)
  object NoulCriteria:
    def apply(whenTrue: String | Null = null, whenFalse: String | Null = null): NoulCriteria =
      fromContent(
        whenTrue  match { case null => null; case s => Content(s) },
        whenFalse match { case null => null; case s => Content(s) },
      )
    def fromContent(whenTrue: Content | Null = null, whenFalse: Content | Null = null): NoulCriteria =
      new NoulCriteria(whenTrue, whenFalse)

  /** The named options of a [[Question.Choice]]. Between 1 and 255
    * entries — enforced at construction, so an out-of-range `Choice`
    * can never be built. Build via [[ChoiceCriteria.apply]] for
    * plain-text descriptions, or [[ChoiceCriteria.fromContent]] for
    * structured (object/array) ones. A `null` description means "the
    * option name is self-explanatory". */
  final class ChoiceCriteria private (val options: Map[String, Content | Null]):
    def size: Int = options.size

  object ChoiceCriteria:
    val MinOptions = 1
    val MaxOptions = 255

    def apply(options: (String, String | Null)*): Either[String, ChoiceCriteria] =
      fromContent(options.map((name, desc) =>
        name -> (desc match { case null => null; case s => Content(s) })
      ).toMap)

    def fromContent(options: Map[String, Content | Null]): Either[String, ChoiceCriteria] =
      if options.size < MinOptions then
        Left(s"Choice.criteria needs at least $MinOptions option, got ${options.size}")
      else if options.size > MaxOptions then
        Left(s"Choice.criteria supports at most $MaxOptions options, got ${options.size}")
      else
        Right(new ChoiceCriteria(options))

  /** The ordered levels of a [[Question.Score]], low to high. Between 2
    * and 10 levels — enforced at construction. Build via
    * [[ScoreCriteria.apply]] for plain-text levels, or
    * [[ScoreCriteria.fromContent]] for structured ones. */
  final class ScoreCriteria private (val levels: List[Content]):
    def size: Int = levels.size

  object ScoreCriteria:
    val MinLevels = 2
    val MaxLevels = 10

    def apply(levels: String*): Either[String, ScoreCriteria] =
      fromContent(levels.map(Content(_)).toList)

    def fromContent(levels: List[Content]): Either[String, ScoreCriteria] =
      if levels.size < MinLevels || levels.size > MaxLevels then
        Left(s"Score.criteria needs between $MinLevels and $MaxLevels levels, got ${levels.size}")
      else
        Right(new ScoreCriteria(levels))

  /** One typed question asked of a piece of `state`. The three cases
    * are exhaustive — Jev has no other question shape.
    *
    * Generic in `S`, the type of `instructions` — a real type parameter,
    * not erased to [[Content]]: `.instructions` reads back exactly the
    * value you built the question with, no decode step, because nothing
    * downstream needs it erased. The wire only needs *some* uniform
    * representation for instructions once several differently-typed
    * questions are combined in one [[ask]] call, and `Helpers` produces
    * that (a `zio.json.ast.Json` node, via the `schema` field below)
    * only at the point of actually building the request — never as part
    * of this type's own public shape.
    *
    * Deliberately a `sealed trait` + plain classes rather than a Scala
    * `enum` or `case class`:
    *
    *   - An `enum` case's inferred type is widened to the enum type
    *     itself wherever it isn't otherwise constrained (e.g. a bare
    *     `NamedTuple` field) — exactly the position [[ask]] infers
    *     `Values` from — which would collapse every question to plain
    *     `Question[?]` and make [[AnswerOf]] unable to pick a branch. A
    *     plain class carries its own precise type, so
    *     `ask(..., (isUrgent = Question.Noul("...")))` infers that
    *     field as `Question.Noul[String]`, not `Question[String]` or
    *     `Question[?]`.
    *   - The public constructor is `apply[S: Schema](instructions: S, ...)`
    *     — a single required argument, so `S` always infers cleanly
    *     from whatever's passed (a `String`, or any `Schema`-derived
    *     record) with an ordinary, visible type-class bound. That needs
    *     a hand-written companion rather than the one a `case class`
    *     synthesizes, since the captured `Schema[S]` has to be stored
    *     alongside `instructions` for `Helpers` to use later.
    *
    * The `NamedTuple` key a `Question` is registered under in [[ask]]
    * becomes both the wire question id and the field name of the
    * matching typed answer in the result — see [[AnswerOf]]. */
  sealed trait Question[S]
  object Question:
    final class Noul[S] private[zio_typesafe_ai] (
      val instructions: S,
      val criteria:      NoulCriteria | Null,
      private[zio_typesafe_ai] val schema: Schema[S],
    ) extends Question[S]
    object Noul:
      def apply[S: Schema](instructions: S, criteria: NoulCriteria | Null = null): Noul[S] =
        new Noul[S](instructions, criteria, summon[Schema[S]])

    final class Choice[S] private[zio_typesafe_ai] (
      val instructions: S,
      val criteria:      ChoiceCriteria,
      private[zio_typesafe_ai] val schema: Schema[S],
    ) extends Question[S]
    object Choice:
      def apply[S: Schema](instructions: S, criteria: ChoiceCriteria): Choice[S] =
        new Choice[S](instructions, criteria, summon[Schema[S]])

    final class Score[S] private[zio_typesafe_ai] (
      val instructions: S,
      val criteria:      ScoreCriteria,
      private[zio_typesafe_ai] val schema: Schema[S],
    ) extends Question[S]
    object Score:
      def apply[S: Schema](instructions: S, criteria: ScoreCriteria): Score[S] =
        new Score[S](instructions, criteria, summon[Schema[S]])

  // ---------- Answers ----------

  /** [[Question.Choice]]'s answer: the selected option, the full
    * probability distribution over every registered option (sums to
    * ~1.0), and the derived [[confidence]]. */
  case class ChoiceAnswer(
    choice:        String,
    probabilities: Map[String, Probability],
    confidence:    Probability,
  )

  /** [[Question.Score]]'s answer: the probability-weighted mean over the
    * registered levels (`Σ level × P(level)`, `0`-indexed, so it can be
    * fractional), the per-level probabilities and legend, and the
    * derived [[confidence]]. */
  case class ScoreAnswer(
    score:         Double,
    legend:        Map[Int, String],
    probabilities: Map[Int, Probability],
    confidence:    Probability,
  )

  /** Per-question answer type, selected by the registered [[Question]]
    * subtype. A `Noul` question answers with a bare [[Probability]] —
    * no wrapper needed, since "the probability the answer is yes" is
    * its entire answer. */
  type AnswerOf[Q] <: Matchable = Q match
    case Question.Noul[?]   => Probability
    case Question.Choice[?] => ChoiceAnswer
    case Question.Score[?]  => ScoreAnswer

  /** Per-tuple-element answer types, in order — the payload half of the
    * `NamedTuple` `.answers` / `.run` return. */
  type AnswersOf[Hs <: Tuple] <: Tuple = Hs match
    case h *: rest  => AnswerOf[h] *: AnswersOf[rest]
    case EmptyTuple => EmptyTuple

  /** Compile-time witness that every element of `Hs` is a [[Question]].
    * Built inductively — as `zio-bedrock-converse`'s `AllTools` is — so
    * a stray non-`Question` element is named in the error rather than
    * producing an opaque match-type failure. */
  sealed trait AllQuestions[Hs <: Tuple]
  object AllQuestions:
    given empty: AllQuestions[EmptyTuple] = new AllQuestions[EmptyTuple] {}

    given consNoul[S, Tail <: Tuple](using AllQuestions[Tail]): AllQuestions[Question.Noul[S] *: Tail] =
      new AllQuestions[Question.Noul[S] *: Tail] {}
    given consChoice[S, Tail <: Tuple](using AllQuestions[Tail]): AllQuestions[Question.Choice[S] *: Tail] =
      new AllQuestions[Question.Choice[S] *: Tail] {}
    given consScore[S, Tail <: Tuple](using AllQuestions[Tail]): AllQuestions[Question.Score[S] *: Tail] =
      new AllQuestions[Question.Score[S] *: Tail] {}

  // ---------- Usage / Result ----------

  case class Usage(inputTokens: Int, outputTokens: Int)

  /** Full response envelope. `answers` is exactly the shape [[ask]]'s
    * `.answers` terminal returns on its own — a `NamedTuple` keyed like
    * the registered questions. */
  case class Result[+T](answers: T, model: ModelId, usage: Usage)

  // ---------- Errors ----------

  sealed trait Error extends Throwable:
    def errorMessage: String
    override def getMessage: String = errorMessage

  object Error:
    final case class BadRequest         (message: String)                 extends Error:
      def errorMessage = s"TypeSafe AI 400 BadRequest: $message"
    final case class Authentication     (message: String)                 extends Error:
      def errorMessage = s"TypeSafe AI 401 Authentication: $message"
    final case class PermissionDenied   (message: String)                 extends Error:
      def errorMessage = s"TypeSafe AI 403 PermissionDenied: $message"
    final case class NotFound           (message: String)                 extends Error:
      def errorMessage = s"TypeSafe AI 404 NotFound: $message"
    final case class UnprocessableEntity(message: String)                 extends Error:
      def errorMessage = s"TypeSafe AI 422 UnprocessableEntity: $message"
    final case class RateLimit          (message: String)                 extends Error:
      def errorMessage = s"TypeSafe AI 429 RateLimit: $message"
    final case class ServiceOverloaded  (message: String)                 extends Error:
      def errorMessage = s"TypeSafe AI 529 ServiceOverloaded: $message"
    final case class InternalServer     (status: Status, message: String) extends Error:
      def errorMessage = s"TypeSafe AI ${status.code} InternalServer: $message"
    final case class Unexpected         (status: Status, body: String)    extends Error:
      def errorMessage = s"TypeSafe AI unexpected ${status.code}: $body"
    final case class Transport          (cause: Throwable)                extends Error:
      def errorMessage = s"Transport error: ${cause.getMessage}"
    final case class MissingApiKey()                                     extends Error:
      def errorMessage = "Missing API key (set TYPESAFE_API_KEY)"
    /** A response that decoded fine as JSON but violated the wire
      * contract — e.g. a `noul` / `confidence` / probability outside
      * `[0, 1]`, or an answer whose `type` doesn't match the question
      * that was asked. */
    final case class MalformedAnswer    (questionId: QuestionId, message: String) extends Error:
      def errorMessage = s"Malformed answer for question '$questionId': $message"
    /** Jev's response omitted an answer for a question that was asked. */
    final case class MissingAnswer      (questionId: QuestionId)          extends Error:
      def errorMessage = s"No answer returned for question '$questionId'"

    private[zio_typesafe_ai] def fromStatus(status: Status, body: String): Error =
      status.code match
        case 400                     => BadRequest(body)
        case 401                     => Authentication(body)
        case 403                     => PermissionDenied(body)
        case 404                     => NotFound(body)
        case 422                     => UnprocessableEntity(body)
        case 429                     => RateLimit(body)
        case 529                     => ServiceOverloaded(body)
        case c if c >= 500 && c < 600 => InternalServer(status, body)
        case _                        => Unexpected(status, body)

    extension [R, A](zio: ZIO[R, Error, A])
      /** Retries `RateLimit` / `ServiceOverloaded` / `InternalServer` up
        * to 2x with exponential backoff — TypeSafe's guidance is to back
        * off rather than retry immediately. */
      def retryOnRetryable: ZIO[R, Error, A] =
        zio.retry(
          Schedule.recurWhile[Error]:
            case _: (RateLimit | ServiceOverloaded | InternalServer) => true
            case _                                                   => false
          && Schedule.exponential(500.millis)
          && Schedule.recurs(2)
        )

  // ---------- Client (trait) ----------

  /** The TypeSafe AI service. Owns the HTTP wire (auth + default model).
    * Two impls live in this package: the live HTTP client (built by
    * `Client.live` / `Client.layer`) and the test-only mock. */
  trait Client:
    /** The model this client targets by default; a request may override
      * it via `.model(...)`. */
    def modelId: ModelId

    /** Single round-trip on the wire. Internal — used by
      * `SystemOneRequest` terminals. */
    private[zio_typesafe_ai] def send(req: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse]

  object Client:
    /** Build a `Client` from an explicit API key and model. */
    def layer(apiKey: ApiKey, modelId: ModelId = ModelId.JevLatest): ZLayer[HClient, Nothing, Client] =
      ZLayer.fromZIO:
        ZIO.serviceWith[HClient](Http.buildClient(apiKey, modelId, _))

    /** Reads `TYPESAFE_API_KEY` (required) and `TYPESAFE_DEFAULT_MODEL`
      * (defaults to `jev-latest`) — the same environment variable names
      * the official Python and JavaScript SDKs use. */
    val live: ZLayer[HClient, Error, Client] =
      ZLayer.fromZIO:
        defer:
          val apiKey =
            ZIO.systemWith(_.env("TYPESAFE_API_KEY")).orDie
              .someOrFail(Error.MissingApiKey()).run
          val modelId =
            ZIO.systemWith(_.env("TYPESAFE_DEFAULT_MODEL")).orDie
              .map(_.fold(ModelId.JevLatest)(ModelId(_))).run
          val client = ZIO.serviceWith[HClient](identity).run
          Http.buildClient(ApiKey(apiKey), modelId, client)

  // ---------- Request builder ----------

  /** Pure-data builder returned by [[ask]]. `.answers` / `.run` pull a
    * `Client` from the ZIO env to make the round-trip.
    *
    * Parameterized directly over a `NamedTuple`'s `Names` and `Values`
    * rather than a single `NT <: NamedTuple.AnyNamedTuple`: with both in
    * hand, `questions.toTuple` (the stdlib's own, compiler-checked
    * unwrap of a `NamedTuple` to its underlying `Values` tuple) resolves
    * directly wherever `NT` is used — an ordinary generic method can't
    * decompose a single opaque `NT` that way, only reach it via the
    * `NamedTuple.Names[NT]` / `NamedTuple.DropNames[NT]` match-type
    * projections, which don't carry enough structure for the extension
    * method to apply and would force falling back to an unchecked
    * `questions.asInstanceOf[Tuple]`. */
  final class SystemOneRequest[Names <: Tuple, Values <: Tuple] @scala.annotation.publicInBinary private[zio_typesafe_ai] (
    private[zio_typesafe_ai] val state:   Content,
    // `Option`, not `ModelId | Null`: this field is a purely internal
    // "did the caller override the model" sentinel that no user ever
    // sees or sets directly (the public surface is `.model(m: ModelId)`,
    // always a real value) — so there's no ergonomic reason to fight
    // opaque-type-vs-null friction here the way the public `T | Null`
    // convention is worth it for a field users actually construct.
    private[zio_typesafe_ai] val model:   Option[ModelId],
    private[zio_typesafe_ai] val entries: List[(QuestionId, Question[?])],
  ):
    /** Override the model for this request; defaults to the `Client`'s
      * configured model. */
    def model(m: ModelId): SystemOneRequest[Names, Values] =
      new SystemOneRequest[Names, Values](state, Some(m), entries)

    /** Just the typed answers, keyed exactly like the registered
      * questions. */
    def answers: ZIO[Client, Error, NamedTuple.NamedTuple[Names, AnswersOf[Values]]] =
      run.map(_.answers)

    /** Full envelope: typed answers plus the responding model and token
      * usage. `Names` / `Values` propagate into `RequestImpl.run`, which
      * returns this exact type — no cast needed here at the public
      * boundary; the one truly unavoidable cast (bridging our own
      * runtime-verified-but-type-checker-opaque decode of each
      * question's answer) lives entirely inside `RequestImpl`. */
    def run: ZIO[Client, Error, Result[NamedTuple.NamedTuple[Names, AnswersOf[Values]]]] =
      RequestImpl.run(this)

  /** Ask Jev one or more questions about `state` in a single round-trip.
    * The `NamedTuple`'s keys become both the wire question ids and the
    * field names of the `.answers` / `.run` result. `state` is generic
    * in a `Schema` type parameter — pass a plain `String`, or any
    * `Schema`-derived record, taxonomy, or database row directly; `S` is
    * inferred from whatever you pass, the same way each [[Question]]
    * factory infers its `instructions`.
    *
    * Compile-time enforcement:
    *   - The tuple is non-empty.
    *   - Every element is a [[Question]].
    *
    * {{{
    * TypeSafeAI.ask(
    *   "Hi, I've been trying to connect my Stripe account for 3 days...",
    *   (
    *     isUrgent   = Question.Noul("Does this message express urgency?"),
    *     department = Question.Choice("Which team should handle this?", deptCriteria),
    *   ),
    * ).answers
    * // : ZIO[Client, Error, (isUrgent: Probability, department: ChoiceAnswer)]
    * }}} */
  inline def ask[S: Schema, Names <: Tuple, Values <: Tuple](
    state:     S,
    questions: NamedTuple.NamedTuple[Names, Values],
  )(using
    inline ev:  Values <:< NonEmptyTuple,
    inline all: AllQuestions[Values],
  ): SystemOneRequest[Names, Values] =
    // `constValueTuple[Names].toList` and `questions.toTuple.toList` are
    // each, individually, a `Tuple` known (only) to be homogeneous in
    // `String` / `Question[?]` by the `using` evidence above — a fact
    // the match-type reducer behind `Tuple#toList` can't consult for an
    // abstract `Names` / `Values`, so it can't narrow past
    // `List[Tuple.Union[_]]` on its own. The casts below are exactly
    // that evidence-to-type-checker bridge, nothing more.
    val names:   List[String]      = compiletime.constValueTuple[Names].toList.asInstanceOf[List[String]]
    val values:  List[Question[?]] = questions.toTuple.toList.asInstanceOf[List[Question[?]]]
    val entries = names.iterator.zip(values.iterator).map((n, q) => (QuestionId(n), q)).toList
    new SystemOneRequest[Names, Values](Content(state), None, entries)
