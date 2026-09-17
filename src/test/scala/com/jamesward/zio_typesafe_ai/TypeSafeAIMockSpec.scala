package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import zio.*
import zio.test.*

/**
 * Every scenario run against the deterministic mock, plus construction
 * tests for the smart constructors ([[ChoiceCriteria]] / [[ScoreCriteria]])
 * that don't need a `Client` at all — the validation lives entirely in
 * the constructor, so it's tested without any wire round-trip.
 */
object TypeSafeAIMockSpec extends ZIOSpecDefault:

  private def asTest(s: SharedSpec.TypeSafeAIScenario): Spec[Any, Any] =
    test(s.name):
      s.run.provideLayer(TypeSafeAIMock(s.mockScript*))

  private val criteriaValidation = suite("criteria smart constructors")(
    test("ChoiceCriteria rejects zero options"):
      assertTrue(ChoiceCriteria().isLeft),
    test("ChoiceCriteria accepts 1 to 255 options"):
      assertTrue(
        ChoiceCriteria("only" -> "the only option").isRight,
        ChoiceCriteria.fromContent((1 to 255).map(i => s"opt$i" -> (null: Content | Null)).toMap).isRight,
      ),
    test("ChoiceCriteria rejects more than 255 options"):
      val tooMany = (1 to 256).map(i => s"opt$i" -> (null: Content | Null)).toMap
      assertTrue(ChoiceCriteria.fromContent(tooMany).isLeft),
    test("ChoiceCriteria treats null as a self-explanatory option"):
      ChoiceCriteria("technical" -> null).fold(
        err => assertNever(s"expected Right, got Left($err)"),
        c   => assertTrue(c.options("technical").asInstanceOf[AnyRef] eq null),
      ),
    test("ScoreCriteria rejects fewer than 2 levels"):
      assertTrue(ScoreCriteria("only one level").isLeft),
    test("ScoreCriteria rejects more than 10 levels"):
      assertTrue(ScoreCriteria.fromContent((1 to 11).map(i => Content(s"level $i")).toList).isLeft),
    test("ScoreCriteria accepts between 2 and 10 levels"):
      assertTrue(
        ScoreCriteria("low", "high").isRight,
        ScoreCriteria.fromContent((1 to 10).map(i => Content(s"level $i")).toList).isRight,
      ),
    test("Probability rejects values outside [0, 1]"):
      assertTrue(Probability(-0.01).isLeft, Probability(1.01).isLeft, Probability(0.0).isRight, Probability(1.0).isRight),
  )

  def spec = suite("TypeSafeAI Mock")(
    criteriaValidation,
    suite("scenarios")(
      (SharedSpec.happyPathScenarios ++ SharedSpec.mockOnlyScenarios).map(asTest)*
    ),
  )
