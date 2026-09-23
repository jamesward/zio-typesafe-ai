package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.internal.Wire
import zio.*
import zio.json.ast.Json
import zio.test.*

object ExchangeObserverSpec extends ZIOSpecDefault:

  private val response = TypeSafeAIMock.MockBehavior.Respond(
    Map("answer" -> Wire.Answer.Noul(0.75)),
    Wire.Usage(7, 3),
  )

  private def program = TypeSafeAI.ask("sensitive state", (answer = Question.Noul("Is it valid?"))).run

  def spec = suite("exchange observation")(
    test("reports the complete request and canonical decoded response exactly once") {
      for
        tracked <- TypeSafeAIMock.tracked(response)
        observations <- Ref.make(List.empty[ExchangeObservation])
        observer = ExchangeObserver.fromFunction(value => observations.update(_ :+ value))
        result <- program.provideLayer(tracked.layer >>> Client.observed(observer))
        seen <- observations.get
      yield
        val request = seen.head.request.asObject.get
        val canonical = seen.head.outcome match
          case ExchangeOutcome.Success(value) => value.asObject
          case ExchangeOutcome.Failure(cause) => throw new AssertionError(s"unexpected failure: ${cause.prettyPrint}")
        assertTrue(
          result.answers.answer.unwrap == 0.75,
          seen.size == 1,
          request.get("state").contains(Json.Str("sensitive state")),
          request.get("model").contains(Json.Str("mock")),
          request.get("questions").flatMap(_.asObject).exists(_.get("answer").isDefined),
          canonical.exists(_.get("model").contains(Json.Str("mock"))),
          canonical.flatMap(_.get("answers")).flatMap(_.asObject).exists(_.get("answer").isDefined),
          canonical.flatMap(_.get("usage")).flatMap(_.asObject).exists(obj =>
            obj.get("input_tokens").contains(Json.Num(7)) && obj.get("output_tokens").contains(Json.Num(3))
          ),
          seen.head.latencyMs >= 0L,
        )
    },
    test("reports a complete failure cause once and re-emits the same error") {
      val failure = Error.RateLimit("slow down")
      for
        tracked <- TypeSafeAIMock.tracked(TypeSafeAIMock.MockBehavior.Fail(failure))
        observations <- Ref.make(List.empty[ExchangeObservation])
        observer = ExchangeObserver.fromFunction(value => observations.update(_ :+ value))
        exit <- program.provideLayer(tracked.layer >>> Client.observed(observer)).exit
        seen <- observations.get
      yield
        val observedError = seen.head.outcome match
          case ExchangeOutcome.Failure(cause) => cause.failureOption
          case ExchangeOutcome.Success(value) => throw new AssertionError(s"unexpected success: $value")
        assertTrue(
          exit.causeOption.flatMap(_.failureOption).contains(failure),
          observedError.contains(failure),
          seen.size == 1,
          seen.head.request.asObject.flatMap(_.get("questions")).isDefined,
        )
    },
    test("observer defects and self-interruption cannot change client results") {
      for
        tracked <- TypeSafeAIMock.tracked(response, response)
        defecting = ExchangeObserver.fromFunction(_ => ZIO.dieMessage("observer defect"))
        interrupting = ExchangeObserver.fromFunction(_ => ZIO.interrupt)
        first <- program.provideLayer(tracked.layer >>> Client.observed(defecting))
        second <- program.provideLayer(tracked.layer >>> Client.observed(interrupting))
        count <- tracked.requestCount
      yield assertTrue(first.answers.answer.unwrap == 0.75, second.answers.answer.unwrap == 0.75, count == 2)
    },
  )
