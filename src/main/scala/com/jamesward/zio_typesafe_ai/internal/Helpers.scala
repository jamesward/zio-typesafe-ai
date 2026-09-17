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
          "criteria"     -> Json.Arr(s.criteria.levels.map(_.json)*),
        )

  private def noulCriteriaField(criteria: NoulCriteria | Null): List[(String, Json)] =
    criteria match
      case null =>
        Nil
      case nc: NoulCriteria =>
        val fields = List(
          contentField("true", nc.whenTrue),
          contentField("false", nc.whenFalse),
        ).flatten
        if fields.isEmpty then Nil else List("criteria" -> Json.Obj(fields*))

  private def contentField(name: String, c: Content | Null): Option[(String, Json)] =
    c match
      case null             => None
      case content: Content => Some(name -> content.json)

  private def contentOrNull(c: Content | Null): Json =
    c match
      case null             => Json.Null
      case content: Content => content.json

  /** Decode one wire `Answer` into the public [[Question]]'s matching
    * answer type. The `(question, answer)` match is exhaustive over
    * every legitimate pairing; a mismatch (Jev answering a `Noul`
    * question with a `choice`, say) is a genuine protocol violation,
    * not a case we failed to enumerate — it falls through to the
    * catch-all as `Error.MalformedAnswer`. */
  def fromWireAnswer(qid: QuestionId, question: Question[?], answer: Wire.Answer): Either[Error, Any] =
    (question, answer) match
      case (_: Question.Noul[?], Wire.Answer.Noul(v)) =>
        Probability(v).left.map(Error.MalformedAnswer(qid, _))

      case (_: Question.Choice[?], Wire.Answer.Choice(choice, probabilities, confidence)) =>
        for
          probs <- traverseProbabilities(probabilities).left.map(Error.MalformedAnswer(qid, _))
          conf  <- Probability(confidence).left.map(Error.MalformedAnswer(qid, _))
        yield ChoiceAnswer(choice, probs, conf)

      case (_: Question.Score[?], Wire.Answer.Score(score, legend, probabilities, confidence)) =>
        for
          lvlLegend <- parseLevelKeys(legend).left.map(Error.MalformedAnswer(qid, _))
          lvlProbs  <- parseLevelKeys(probabilities).left.map(Error.MalformedAnswer(qid, _))
          probs     <- traverseProbabilities(lvlProbs).left.map(Error.MalformedAnswer(qid, _))
          conf      <- Probability(confidence).left.map(Error.MalformedAnswer(qid, _))
        yield ScoreAnswer(score, lvlLegend, probs, conf)

      case _ =>
        Left(Error.MalformedAnswer(qid, s"answer type doesn't match the question that was asked: $answer"))

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
