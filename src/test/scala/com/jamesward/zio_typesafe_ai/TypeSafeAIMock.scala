package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.{Error, ModelId}
import com.jamesward.zio_typesafe_ai.internal.Wire
import zio.*

import scala.collection.immutable.Queue

/**
 * Test-side mock for `TypeSafeAI.Client`. Scripts Jev's behavior as a
 * queue of [[MockBehavior]]s; each `send` consumes one and returns (or
 * fails with) the matching wire response.
 *
 * Unlike `zio-bedrock-converse`'s multi-turn mock, one `send` here is
 * one whole `ask(...).run` — System One answers every registered
 * question in a single round-trip, so one behavior scripts one call.
 *
 * Lives in `src/test` so the production jar carries no test-only code.
 */
object TypeSafeAIMock:

  sealed trait MockBehavior

  object MockBehavior:
    /** Jev answers every registered question. */
    case class Respond(answers: Map[String, Wire.Answer], usage: Wire.Usage = Wire.Usage(0, 0)) extends MockBehavior

    /** The call fails with the given error. */
    case class Fail(error: Error) extends MockBehavior

  /** A `TypeSafeAI.Client` layer whose responses come from `behaviors`. */
  def apply(behaviors: MockBehavior*): ULayer[TypeSafeAI.Client] =
    ZLayer.fromZIO:
      Ref.make(Queue.from(behaviors.toIndexedSeq)).map: ref =>
        new TypeSafeAI.Client:
          val modelId: ModelId = ModelId("mock")
          def send(req: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse] =
            ref.modify:
              case q if q.isEmpty => (None, q)
              case q              => val (head, rest) = q.dequeue; (Some(head), rest)
            .flatMap:
              case None =>
                ZIO.fail(Error.Unexpected(
                  zio.http.Status.InternalServerError,
                  "Mock script exhausted: more `send` rounds than scripted behaviors",
                ))
              case Some(MockBehavior.Respond(answers, usage)) =>
                // `req.body` is the assembled request JSON, not a typed
                // case class — nothing here needs to echo the real
                // requested model back, so just report the mock's own id.
                ZIO.succeed(Wire.SystemOneResponse(model = modelId.unwrap, answers = answers, usage = usage))
              case Some(MockBehavior.Fail(error)) =>
                ZIO.fail(error)
