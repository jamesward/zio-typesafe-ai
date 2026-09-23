package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.internal.Helpers
import zio.json.ast.Json
import zio.schema.{Schema, derived}
import zio.schema.codec.json.schemaJson
import zio.test.*

object AdvancedPrimitivesSpec extends ZIOSpecDefault:

  private case class TypedInstructions(question: String, compare: List[String]) derives Schema
  private case class TypedBoundary(definition: String, examples: List[String]) derives Schema

  private val instructionShapes: List[(String, Json)] = List(
    "string" -> Json.Str("Classify this value"),
    "object" -> Json.Obj(
      "question" -> Json.Str("Which field matches?"),
      "compare" -> Json.Arr(Json.Str("name"), Json.Str("description")),
    ),
    "array" -> Json.Arr(Json.Str("Check the title"), Json.Str("Check the body")),
    "null" -> Json.Null,
  )

  private val choiceCriteria = ChoiceCriteria("only" -> null).toOption.get
  private val scoreCriteria = ScoreCriteria("low", "high").toOption.get

  private def encodedQuestions(entries: List[(QuestionId, Question[?])]): Json.Obj =
    Helpers
      .toWireRequest(Content("state"), ModelId.JevLatest.unwrap, entries)
      .body
      .asObject
      .flatMap(_.get("questions"))
      .flatMap(_.asObject)
      .get

  def spec = suite("advanced structured primitives")(
    test("typed Schema[T] values preserve records, collections, options, and criteria"):
      val instructions = TypedInstructions("Which field matches?", List("name", "description"))
      val boundary = TypedBoundary("A precise match", List("same normalized identifier"))
      val choice = ChoiceCriteria.fromContent(Map("match" -> Content(boundary))).toOption.get
      val score = ScoreCriteria.fromContent(List(Content(boundary), null)).toOption.get
      val noul = NoulCriteria.fromContent(Content(boundary), null)
      val questions = encodedQuestions(List(
        QuestionId("record") -> Question.Choice(instructions, choice),
        QuestionId("collection") -> Question.Score(List("check title", "check body"), score),
        QuestionId("optional") -> Question.Noul(Option.empty[String], noul),
      ))
      val record = questions.get("record").flatMap(_.asObject).get
      val collection = questions.get("collection").flatMap(_.asObject).get
      val optional = questions.get("optional").flatMap(_.asObject).get
      val expectedBoundary = Json.Obj(
        "definition" -> Json.Str("A precise match"),
        "examples" -> Json.Arr(Json.Str("same normalized identifier")),
      )

      assertTrue(
        record.get("instructions").contains(Json.Obj(
          "question" -> Json.Str("Which field matches?"),
          "compare" -> Json.Arr(Json.Str("name"), Json.Str("description")),
        )),
        record.get("criteria").flatMap(_.asObject).flatMap(_.get("match")).contains(expectedBoundary),
        collection.get("instructions").contains(Json.Arr(Json.Str("check title"), Json.Str("check body"))),
        collection.get("criteria").contains(Json.Arr(expectedBoundary, Json.Null)),
        optional.get("instructions").contains(Json.Null),
        optional.get("criteria").flatMap(_.asObject).flatMap(_.get("true")).contains(expectedBoundary),
        optional.get("criteria").flatMap(_.asObject).flatMap(_.get("false")).contains(Json.Null),
      )
    ,
    test("dynamic Json values preserve every primitive instruction shape"):
      val entries = instructionShapes.flatMap: (shapeName, value) =>
        List(
          QuestionId(s"noul_$shapeName") -> Question.Noul(value),
          QuestionId(s"choice_$shapeName") -> Question.Choice(value, choiceCriteria),
          QuestionId(s"score_$shapeName") -> Question.Score(value, scoreCriteria),
        )
      val questions = encodedQuestions(entries)

      assertTrue(instructionShapes.forall: (shapeName, expected) =>
        List("noul", "choice", "score").forall: primitive =>
          questions
            .get(s"${primitive}_$shapeName")
            .flatMap(_.asObject)
            .flatMap(_.get("instructions"))
            .contains(expected)
      )
    ,
    test("Choice and Score criteria preserve every EntryType shape"):
      val descriptions = instructionShapes.map: (name, value) =>
        name -> (if name == "null" then null else Content(value))
      val choice = ChoiceCriteria.fromContent(descriptions.toMap).toOption.get
      val score = ScoreCriteria.fromContent(descriptions.map(_._2)).toOption.get
      val questions = encodedQuestions(List(
        QuestionId("choice") -> Question.Choice("Choose", choice),
        QuestionId("score") -> Question.Score("Score", score),
      ))
      val encodedChoice = questions.get("choice").flatMap(_.asObject).flatMap(_.get("criteria")).flatMap(_.asObject).get
      val encodedScore = questions.get("score").flatMap(_.asObject).flatMap(_.get("criteria")).get

      assertTrue(
        instructionShapes.forall((name, expected) => encodedChoice.get(name).contains(expected)),
        encodedScore.equals(Json.Arr(instructionShapes.map(_._2)*)),
      )
    ,
    test("Noul criteria are optional and preserve structured or null boundaries"):
      val objectBoundary = instructionShapes.toMap.apply("object")
      val arrayBoundary = instructionShapes.toMap.apply("array")
      val questions = encodedQuestions(List(
        QuestionId("structured") -> Question.Noul(
          "Check a subtle boundary",
          NoulCriteria.fromContent(Content(objectBoundary), Content(arrayBoundary)),
        ),
        QuestionId("nullable") -> Question.Noul(
          "Check a nullable boundary",
          NoulCriteria(whenTrue = "Evidence is present", whenFalse = null),
        ),
        QuestionId("omitted") -> Question.Noul("No boundary needed"),
      ))
      val structured = questions.get("structured").flatMap(_.asObject).flatMap(_.get("criteria")).flatMap(_.asObject).get
      val nullable = questions.get("nullable").flatMap(_.asObject).flatMap(_.get("criteria")).flatMap(_.asObject).get
      val omitted = questions.get("omitted").flatMap(_.asObject).get

      assertTrue(
        structured.get("true").contains(objectBoundary),
        structured.get("false").contains(arrayBoundary),
        nullable.get("true").contains(Json.Str("Evidence is present")),
        nullable.get("false").contains(Json.Null),
        !omitted.get("criteria").isDefined,
      )
    ,
  )
