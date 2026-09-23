package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.internal.{Helpers, Wire}
import zio.*
import zio.json.ast.Json
import zio.schema.codec.json.schemaJson
import zio.test.*

object DynamicRequestSpec extends ZIOSpecDefault:

  private val routeCriteria = ChoiceCriteria(
    "resolver" -> "Resolve a version",
    "list" -> "List records",
  ).toOption.get

  private val scoreCriteria = ScoreCriteria("low", "medium", "high").toOption.get

  private val questions: List[(QuestionId, Question[?])] = List(
    QuestionId("needed_resolver") -> Question.Noul("Is a resolver needed?"),
    QuestionId("route") -> Question.Choice("Which stage is this?", routeCriteria),
    QuestionId("relevance") -> Question.Score("How relevant is it?", scoreCriteria),
  )

  private val response = TypeSafeAIMock.MockBehavior.Respond(
    Map(
      "needed_resolver" -> Wire.Answer.Noul(0.91),
      "route" -> Wire.Answer.Choice(
        "resolver",
        Map("resolver" -> 0.8, "list" -> 0.2),
        0.6,
      ),
      "relevance" -> Wire.Answer.Score(
        1.7,
        Map("0" -> "low", "1" -> "medium", "2" -> "high"),
        Map("0" -> 0.0, "1" -> 0.3, "2" -> 0.7),
        0.58,
      ),
    ),
    Wire.Usage(123, 17),
  )

  def spec = suite("dynamic Jev requests")(
    test("runtime-sized mixed questions decode to DynamicAnswer variants") {
      val program = for
        request <- ZIO.fromEither(askDynamic("state", questions))
        result <- request.run
      yield
        val noul = result.answers.get(QuestionId("needed_resolver"))
        val choice = result.answers.get(QuestionId("route"))
        val score = result.answers.get(QuestionId("relevance"))
        assertTrue(
          noul.exists {
            case DynamicAnswer.Noul(probability) => probability.unwrap == 0.91
            case _                               => false
          },
          choice.exists {
            case DynamicAnswer.Choice(answer) => answer.choice == "resolver" && answer.confidence.unwrap == 0.6
            case _                            => false
          },
          score.exists {
            case DynamicAnswer.Score(answer) => answer.score == 1.7 && answer.probabilities(2).unwrap == 0.7
            case _                           => false
          },
          result.usage.inputTokens == 123,
          result.usage.outputTokens == 17,
        )

      program.provideLayer(TypeSafeAIMock(response))
    },
    test("askDynamic rejects an empty batch") {
      assertTrue(askDynamic("state", List.empty).isLeft)
    },
    test("askDynamic rejects duplicate question ids") {
      val duplicates = List(
        QuestionId("same") -> Question.Noul("first"),
        QuestionId("same") -> Question.Noul("second"),
      )
      assertTrue(askDynamic("state", duplicates).left.exists(_.contains("same")))
    },
    test("missing dynamic answer surfaces MissingAnswer") {
      val program = for
        request <- ZIO.fromEither(askDynamic("state", questions.take(1)))
        result <- request.answers
      yield result

      program.provideLayer(TypeSafeAIMock(TypeSafeAIMock.MockBehavior.Respond(Map.empty))).either.map:
        case Left(error: Error.MissingAnswer) => assertTrue(error.questionId == QuestionId("needed_resolver"))
        case Left(other)                      => assertNever(s"expected MissingAnswer, got $other")
        case Right(value)                     => assertNever(s"expected failure, got $value")
    },
    test("structured Choice matches the documented state/model/questions wire shape") {
      val state: Json = Json.Obj(
        "currentPlayerPiece" -> Json.Str("J"),
        "boardRowsTopToBottom" -> Json.Arr(
          Json.Arr(Json.Str("."), Json.Str("."), Json.Str(".")),
          Json.Arr(Json.Str("J"), Json.Str("J"), Json.Str(".")),
        ),
      )
      val instructions: Json = Json.Obj(
        "task" -> Json.Str("Choose a column using `boardRowsTopToBottom`"),
        "rules" -> Json.Arr(Json.Str("Rows are ordered top to bottom")),
      )
      val criteria = ChoiceCriteria.fromContent(Map[String, Content | Null](
        "column_0" -> null,
        "column_1" -> null,
        "column_2" -> null,
      )).toOption.get
      val entries: List[(QuestionId, Question[?])] = List(
        QuestionId("move") -> Question.Choice(instructions, criteria)
      )
      val request = askDynamic(state, entries).toOption.get
      val wire = Helpers.toWireRequest(request.state, ModelId.JevLatest.unwrap, request.entries)
      val expected: Json = Json.Obj(
        "state" -> state,
        "model" -> Json.Str("jev-latest"),
        "questions" -> Json.Obj(
          "move" -> Json.Obj(
            "type" -> Json.Str("choice"),
            "instructions" -> instructions,
            "criteria" -> Json.Obj(
              "column_0" -> Json.Null,
              "column_1" -> Json.Null,
              "column_2" -> Json.Null,
            ),
          )
        ),
      )
      assertTrue(wire.body.equals(expected))
    },
  )
