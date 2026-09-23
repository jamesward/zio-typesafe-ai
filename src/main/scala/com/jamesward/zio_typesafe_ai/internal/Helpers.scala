package com.jamesward.zio_typesafe_ai.internal

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import zio.json.ast.Json

/** Wire ↔ public translation for [[TypeSafeAI]]. Never imported by users. */
private[zio_typesafe_ai] object Helpers:

  /** Assemble the full request body. `state` is already a [[Content]]
    * (converted at `ask(...)` construction time); each question's
    * `instructions` is converted here, at the last possible moment,
    * using the `Schema[S]` it captured at its own construction. */
  def toWireRequest(state: Content, model: String, entries: List[(QuestionId, Question[?])]): Wire.SystemOneRequest =
    Wire.SystemOneRequest(Json.Obj(
      "state"     -> state.json,
      "model"     -> Json.Str(model),
      "questions" -> Json.Obj(entries.map((qid, q) => qid.unwrap -> questionJson(q))*),
    ))

  private def questionJson(q: Question[?]): Json =
    q match
      case n: Question.Noul[s] =>
        val fields = List("type" -> Json.Str("noul"), "instructions" -> Codecs.toJsonAst(n.instructions)(using n.schema))
          ++ noulCriteriaField(n.criteria)
        Json.Obj(fields*)
      case c: Question.Choice[s] =>
        Json.Obj(
          "type"         -> Json.Str("choice"),
          "instructions" -> Codecs.toJsonAst(c.instructions)(using c.schema),
          "criteria"     -> Json.Obj(c.criteria.options.toSeq.map((name, desc) => name -> contentOrNull(desc))*),
        )
      case s: Question.Score[s] =>
        Json.Obj(
          "type"         -> Json.Str("score"),
          "instructions" -> Codecs.toJsonAst(s.instructions)(using s.schema),
          "criteria"     -> Json.Arr(s.criteria.levels.map(contentOrNull)*),
        )

  private def noulCriteriaField(criteria: NoulCriteria | Null): List[(String, Json)] =
    criteria match
      case null =>
        Nil
      case nc: NoulCriteria =>
        List("criteria" -> Json.Obj(
          "true"  -> contentOrNull(nc.whenTrue),
          "false" -> contentOrNull(nc.whenFalse),
        ))

  private def contentOrNull(c: Content | Null): Json =
    c match
      case null             => Json.Null
      case content: Content => content.json

  /** Decode one wire answer while retaining its runtime Jev variant. */
  def fromWireDynamicAnswer(
    qid: QuestionId,
    question: Question[?],
    answer: Wire.Answer,
  ): Either[Error, DynamicAnswer] =
    (question, answer) match
      case (_: Question.Noul[?], Wire.Answer.Noul(v)) =>
        Probability(v)
          .left.map(Error.MalformedAnswer(qid, _))
          .map(DynamicAnswer.Noul(_))

      case (_: Question.Choice[?], Wire.Answer.Choice(choice, probabilities, confidence)) =>
        for
          probs <- traverseProbabilities(probabilities).left.map(Error.MalformedAnswer(qid, _))
          conf  <- Probability(confidence).left.map(Error.MalformedAnswer(qid, _))
        yield DynamicAnswer.Choice(ChoiceAnswer(choice, probs, conf))

      case (_: Question.Score[?], Wire.Answer.Score(score, legend, probabilities, confidence)) =>
        for
          lvlLegend <- parseLevelKeys(legend).left.map(Error.MalformedAnswer(qid, _))
          lvlProbs  <- parseLevelKeys(probabilities).left.map(Error.MalformedAnswer(qid, _))
          probs     <- traverseProbabilities(lvlProbs).left.map(Error.MalformedAnswer(qid, _))
          conf      <- Probability(confidence).left.map(Error.MalformedAnswer(qid, _))
        yield DynamicAnswer.Score(ScoreAnswer(score, lvlLegend, probs, conf))

      case _ =>
        Left(Error.MalformedAnswer(qid, s"answer type doesn't match the question that was asked: $answer"))

  /** Decode one wire `Answer` for the compile-time NamedTuple path. */
  def fromWireAnswer(qid: QuestionId, question: Question[?], answer: Wire.Answer): Either[Error, Any] =
    fromWireDynamicAnswer(qid, question, answer).map:
      case DynamicAnswer.Noul(probability) => probability
      case DynamicAnswer.Choice(value)     => value
      case DynamicAnswer.Score(value)      => value

  private def traverseProbabilities[K](m: Map[K, Double]): Either[String, Map[K, Probability]] =
    m.foldLeft[Either[String, Map[K, Probability]]](Right(Map.empty)):
      case (acc, (k, v)) =>
        for
          soFar <- acc
          p     <- Probability(v)
        yield soFar.updated(k, p)

  private def parseLevelKeys[A](m: Map[String, A]): Either[String, Map[Int, A]] =
    m.foldLeft[Either[String, Map[Int, A]]](Right(Map.empty)):
      case (acc, (k, v)) =>
        for
          soFar <- acc
          level <- k.toIntOption.toRight(s"non-numeric score level key: '$k'")
        yield soFar.updated(level, v)
