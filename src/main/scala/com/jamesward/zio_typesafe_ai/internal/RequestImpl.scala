package com.jamesward.zio_typesafe_ai.internal

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import zio.*

/** Implements typed and runtime-sized System One requests: build the wire
  * request, send it, and decode every registered answer. */
private[zio_typesafe_ai] object RequestImpl:

  /** Execute the compile-time NamedTuple request. The one cast bridges the
    * runtime-verified tuple back to the exact `AnswersOf[Values]` shape. */
  def run[Names <: Tuple, Values <: Tuple](
    req: SystemOneRequest[Names, Values],
  ): ZIO[Client, Error, Result[NamedTuple.NamedTuple[Names, AnswersOf[Values]]]] =
    ZIO.serviceWithZIO[Client]: client =>
      val model = req.model.getOrElse(client.modelId)
      val wireReq = Helpers.toWireRequest(req.state, model.unwrap, req.entries)
      client.send(wireReq).flatMap: wire =>
        ZIO.fromEither(decodeAnswers(req.entries, wire)).map: answers =>
          Result(
            answers = answers.asInstanceOf[NamedTuple.NamedTuple[Names, AnswersOf[Values]]],
            model = ModelId(wire.model),
            usage = Usage(wire.usage.inputTokens, wire.usage.outputTokens),
          )

  /** Execute a runtime-sized question batch and retain each answer's Jev
    * variant in a map keyed by the caller's QuestionId. */
  def runDynamic(
    req: DynamicSystemOneRequest,
  ): ZIO[Client, Error, Result[Map[QuestionId, DynamicAnswer]]] =
    ZIO.serviceWithZIO[Client]: client =>
      val model = req.model.getOrElse(client.modelId)
      val wireReq = Helpers.toWireRequest(req.state, model.unwrap, req.entries)
      client.send(wireReq).flatMap: wire =>
        ZIO.fromEither(decodeDynamicAnswers(req.entries, wire)).map: answers =>
          Result(
            answers = answers,
            model = ModelId(wire.model),
            usage = Usage(wire.usage.inputTokens, wire.usage.outputTokens),
          )

  private def decodeDynamicAnswers(
    entries: List[(QuestionId, Question[?])],
    wire: Wire.SystemOneResponse,
  ): Either[Error, Map[QuestionId, DynamicAnswer]] =
    entries.foldLeft[Either[Error, Map[QuestionId, DynamicAnswer]]](Right(Map.empty)):
      case (acc, (qid, question)) =>
        for
          answers <- acc
          wireAnswer <- wire.answers.get(qid.unwrap).toRight(Error.MissingAnswer(qid))
          answer <- Helpers.fromWireDynamicAnswer(qid, question, wireAnswer)
        yield answers.updated(qid, answer)

  /** Decode every registered typed question in NamedTuple order. */
  private def decodeAnswers(
    entries: List[(QuestionId, Question[?])],
    wire: Wire.SystemOneResponse,
  ): Either[Error, Tuple] =
    entries.foldRight[Either[Error, Tuple]](Right(EmptyTuple)):
      case ((qid, question), acc) =>
        for
          rest <- acc
          wireAnswer <- wire.answers.get(qid.unwrap).toRight(Error.MissingAnswer(qid))
          answer <- Helpers.fromWireAnswer(qid, question, wireAnswer)
        yield answer *: rest
