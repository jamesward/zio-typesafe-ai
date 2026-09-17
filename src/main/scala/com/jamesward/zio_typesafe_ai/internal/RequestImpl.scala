package com.jamesward.zio_typesafe_ai.internal

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import zio.*

/** Implements `SystemOneRequest#run`: build the wire request, send it,
  * and decode each registered question's answer back in NamedTuple
  * order. Lives here (rather than inline in `TypeSafeAI.scala`) purely
  * to keep the public file free of wire plumbing. */
private[zio_typesafe_ai] object RequestImpl:

  /** Generic in `Names` / `Values` — the same pair `SystemOneRequest`
    * carries — so this returns the exact type `SystemOneRequest#run`
    * declares. That confines the one cast this whole round-trip needs
    * (turning the `Tuple` `decodeAnswers` builds at runtime into the
    * `NamedTuple` the caller's compile-time `Values` promises) to a
    * single line here, instead of a second, redundant cast at the
    * public boundary. */
  def run[Names <: Tuple, Values <: Tuple](
    req: SystemOneRequest[Names, Values],
  ): ZIO[Client, Error, Result[NamedTuple.NamedTuple[Names, AnswersOf[Values]]]] =
    ZIO.serviceWithZIO[Client]: client =>
      val model   = req.model.getOrElse(client.modelId)
      val wireReq = Helpers.toWireRequest(req.state, model.unwrap, req.entries)
      client.send(wireReq).flatMap: wire =>
        ZIO.fromEither(decodeAnswers(req.entries, wire)).map: answers =>
          Result(
            answers = answers.asInstanceOf[NamedTuple.NamedTuple[Names, AnswersOf[Values]]],
            model   = ModelId(wire.model),
            usage   = Usage(wire.usage.inputTokens, wire.usage.outputTokens),
          )

  /** Decode every registered question's answer, in the same order the
    * `NamedTuple` declared them. The `Tuple` this builds is, by
    * construction, shaped exactly like `AnswersOf[Values]` — each
    * element decoded via the matching entry's `Question` — but nothing
    * here ties that fact to the type checker; `run` casts it once,
    * right after this returns. */
  private def decodeAnswers(
    entries: List[(QuestionId, Question[?])],
    wire:    Wire.SystemOneResponse,
  ): Either[Error, Tuple] =
    entries.foldRight[Either[Error, Tuple]](Right(EmptyTuple)):
      case ((qid, question), acc) =>
        for
          rest       <- acc
          wireAnswer <- wire.answers.get(qid.unwrap).toRight(Error.MissingAnswer(qid))
          answer     <- Helpers.fromWireAnswer(qid, question, wireAnswer)
        yield answer *: rest
