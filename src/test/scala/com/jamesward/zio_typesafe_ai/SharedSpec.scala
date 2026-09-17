package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.internal.Wire
import zio.*
import zio.direct.*
import zio.test.{TestResult, assertCompletes, assertNever, assertTrue}

/**
 * Scenario definitions shared between the mock and integration specs.
 *
 * The "happy path" scenarios assert only on the *shape* of an answer
 * (a probability is in `[0,1]`, a choice is one of the registered
 * options, ...) plus one semantically-obvious fact about an easy
 * example — the same trust-the-model-on-easy-prompts stance
 * `zio-bedrock-converse`'s shared scenarios take — so the same list
 * runs against both the deterministic mock and the live model. The
 * protocol-error scenarios script responses no real model would
 * produce (a missing answer, an out-of-range probability) and so are
 * mock-only.
 */
object SharedSpec:

  trait TypeSafeAIScenario:
    def name: String
    def run: ZIO[TypeSafeAI.Client, Any, TestResult]
    def mockScript: List[TypeSafeAIMock.MockBehavior]

  // ---------- Shared fixtures ----------

  val stripeMessage: String =
    "Hi, I've been trying to connect my Stripe account for 3 days and it keeps failing. I'm losing sales. Please help ASAP."

  val departmentCriteria: ChoiceCriteria =
    ChoiceCriteria(
      "billing"   -> "Payment or subscription issues",
      "technical" -> "Bugs or integration problems",
      "sales"     -> "Pricing or account questions",
    ).getOrElse(throw new IllegalArgumentException("fixture criteria should be valid"))

  val frustrationCriteria: ScoreCriteria =
    ScoreCriteria(
      "Calm, just stating facts",
      "Frustrated but civil",
      "Very angry, strong language",
    ).getOrElse(throw new IllegalArgumentException("fixture criteria should be valid"))

  // ---------- Happy-path scenarios (mock + live) ----------

  val noulScenario: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "noul: an obviously urgent message answers with high probability"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Respond(Map(
      "isUrgent" -> Wire.Answer.Noul(0.999),
    )))
    def run = defer:
      val answers = ask(
        stripeMessage,
        (isUrgent = Question.Noul("Does this message convey urgency or time pressure?")),
      ).answers.run
      val p = answers.isUrgent.unwrap
      assertTrue(p >= 0.0, p <= 1.0, p > 0.5)

  val choiceScenario: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "choice: an obviously technical message routes to the technical option"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Respond(Map(
      "department" -> Wire.Answer.Choice(
        choice        = "technical",
        probabilities = Map("billing" -> 0.159, "technical" -> 0.84, "sales" -> 0.001),
        confidence    = 0.596,
      ),
    )))
    def run = defer:
      val answers = ask(
        stripeMessage,
        (department = Question.Choice("Which team should handle this support message?", departmentCriteria)),
      ).answers.run
      // Not asserting *which* option wins here: "Stripe" reasonably reads
      // as either a billing/payments issue or a technical integration
      // issue, and a live model is entitled to land on either — that
      // judgment call belongs to Jev, not this test. `choiceExactMatch`
      // below pins the exact decode against a scripted, unambiguous
      // response instead.
      val d = answers.department
      assertTrue(
        departmentCriteria.options.keySet.contains(d.choice),
        d.probabilities.keySet == departmentCriteria.options.keySet,
        d.confidence.unwrap >= 0.0,
        d.confidence.unwrap <= 1.0,
      )

  val choiceExactMatch: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "choice: decodes the scripted choice, probabilities, and confidence exactly (mock-only)"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Respond(Map(
      "department" -> Wire.Answer.Choice(
        choice        = "technical",
        probabilities = Map("billing" -> 0.159, "technical" -> 0.84, "sales" -> 0.001),
        confidence    = 0.596,
      ),
    )))
    def run = defer:
      val answers = ask(
        stripeMessage,
        (department = Question.Choice("Which team should handle this support message?", departmentCriteria)),
      ).answers.run
      val d = answers.department
      assertTrue(
        d.choice == "technical",
        d.probabilities("technical").unwrap == 0.84,
        d.confidence.unwrap == 0.596,
      )

  val scoreScenario: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "score: a frustrated message rates above the calmest level"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Respond(Map(
      "frustration" -> Wire.Answer.Score(
        score         = 1.3,
        legend        = Map("0" -> "Calm, just stating facts", "1" -> "Frustrated but civil", "2" -> "Very angry, strong language"),
        probabilities = Map("0" -> 0.0, "1" -> 0.7, "2" -> 0.3),
        confidence    = 0.54,
      ),
    )))
    def run = defer:
      val answers = ask(
        stripeMessage,
        (frustration = Question.Score("How frustrated does the customer sound?", frustrationCriteria)),
      ).answers.run
      val f = answers.frustration
      assertTrue(
        f.score >= 0.0,
        f.score <= (frustrationCriteria.size - 1).toDouble,
        f.score > 0.0,
        f.legend.keySet == f.probabilities.keySet,
      )

  val combinedScenario: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "combined: Noul + Choice + Score answered in one round-trip, each typed correctly"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Respond(
      Map(
        "isUrgent"    -> Wire.Answer.Noul(0.999),
        "department"  -> Wire.Answer.Choice("technical", Map("billing" -> 0.16, "technical" -> 0.84, "sales" -> 0.0), 0.6),
        "frustration" -> Wire.Answer.Score(1.3, Map("0" -> "Calm", "1" -> "Frustrated", "2" -> "Angry"), Map("0" -> 0.0, "1" -> 0.7, "2" -> 0.3), 0.54),
      ),
      Wire.Usage(inputTokens = 312, outputTokens = 48),
    ))
    def run = defer:
      val result = ask(
        stripeMessage,
        (
          isUrgent    = Question.Noul("Does this message convey urgency or time pressure?"),
          department  = Question.Choice("Which team should handle this?", departmentCriteria),
          frustration = Question.Score("How frustrated does the customer sound?", frustrationCriteria),
        ),
      ).run.run
      assertTrue(
        result.answers.isUrgent.unwrap > 0.5,
        departmentCriteria.options.keySet.contains(result.answers.department.choice),
        result.answers.frustration.score >= 0.0,
        result.usage.inputTokens >= 0,
        result.usage.outputTokens >= 0,
      )

  val happyPathScenarios: List[TypeSafeAIScenario] = List(
    noulScenario,
    choiceScenario,
    scoreScenario,
    combinedScenario,
  )

  // ---------- Protocol-error scenarios (mock only) ----------

  val missingAnswerScenario: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "missing answer for a registered question -> Error.MissingAnswer"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Respond(Map.empty))
    def run =
      ask(stripeMessage, (isUrgent = Question.Noul("Urgent?"))).answers.either.map:
        case Left(e: Error.MissingAnswer) => assertTrue(e.questionId == QuestionId("isUrgent"))
        case Left(other)                  => assertNever(s"expected MissingAnswer, got $other")
        case Right(r)                     => assertNever(s"expected failure, got $r")

  val malformedAnswerScenario: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "noul value outside [0,1] -> Error.MalformedAnswer"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Respond(Map(
      "isUrgent" -> Wire.Answer.Noul(1.5),
    )))
    def run =
      ask(stripeMessage, (isUrgent = Question.Noul("Urgent?"))).answers.either.map:
        case Left(_: Error.MalformedAnswer) => assertCompletes
        case Left(other)                    => assertNever(s"expected MalformedAnswer, got $other")
        case Right(r)                       => assertNever(s"expected failure, got $r")

  val mismatchedAnswerTypeScenario: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "answer type doesn't match the question asked -> Error.MalformedAnswer"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Respond(Map(
      "isUrgent" -> Wire.Answer.Choice("yes", Map("yes" -> 1.0), 1.0),
    )))
    def run =
      ask(stripeMessage, (isUrgent = Question.Noul("Urgent?"))).answers.either.map:
        case Left(_: Error.MalformedAnswer) => assertCompletes
        case Left(other)                    => assertNever(s"expected MalformedAnswer, got $other")
        case Right(r)                       => assertNever(s"expected failure, got $r")

  val serviceFailureScenario: TypeSafeAIScenario = new TypeSafeAIScenario:
    val name = "service failure propagates as the typed Error"
    val mockScript = List(TypeSafeAIMock.MockBehavior.Fail(Error.RateLimit("slow down")))
    def run =
      ask(stripeMessage, (isUrgent = Question.Noul("Urgent?"))).answers.either.map:
        case Left(_: Error.RateLimit) => assertCompletes
        case Left(other)              => assertNever(s"expected RateLimit, got $other")
        case Right(r)                 => assertNever(s"expected failure, got $r")

  val mockOnlyScenarios: List[TypeSafeAIScenario] = List(
    choiceExactMatch,
    missingAnswerScenario,
    malformedAnswerScenario,
    mismatchedAnswerTypeScenario,
    serviceFailureScenario,
  )
