package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.internal.Wire
import zio.*
import zio.test.*

object LoopSpec extends ZIOSpecDefault:

  private def response(choice: String, input: Int, output: Int) =
    TypeSafeAIMock.MockBehavior.Respond(
      Map("next_action" -> Wire.Answer.Choice(
        choice,
        Map("increment" -> 0.7, "finish" -> 0.3),
        0.4,
      )),
      Wire.Usage(input, output),
    )

  private def request(max: Int = 10) =
    TypeSafeAI.loop[Int, String, Any, Nothing, Int](0)(
      state => Content(state),
      _ => ZIO.succeed(NonEmptyChunk(
        LoopOption.text("increment", "increment", "Increase the state by one."),
        LoopOption.text("finish", "finish", "Return the current state."),
      )),
    ) { (state, action) =>
      action match
        case "increment" => ZIO.succeed(LoopStep.Continue(state + 1))
        case "finish"    => ZIO.succeed(LoopStep.Done(state))
    }.maxIterations(max)

  def spec = suite("TypeSafeAI loop")(
    test("continues with new state, completes, and aggregates usage") {
      request().run.provideLayer(TypeSafeAIMock(
        response("increment", 10, 2),
        response("increment", 11, 2),
        response("finish", 12, 3),
      )).map: result =>
        assertTrue(
          result.output == 2,
          result.turns.map(_.choice) == List("increment", "increment", "finish"),
          result.turns.map(_.iteration) == List(1, 2, 3),
          result.usage.inputTokens == 33,
          result.usage.outputTokens == 7,
        )
    },
    test("fails when Jev selects an unknown option") {
      request().run.provideLayer(TypeSafeAIMock(response("unknown", 1, 1))).either.map:
        case Left(_: Error.InvalidLoop) => assertCompletes
        case other                      => assertNever(s"expected InvalidLoop, got $other")
    },
    test("enforces maxIterations") {
      request(max = 1).run.provideLayer(TypeSafeAIMock(response("increment", 1, 1))).either.map:
        case Left(_: Error.MaxIterations) => assertCompletes
        case other                         => assertNever(s"expected MaxIterations, got $other")
    },
    test("rejects duplicate option ids before sending") {
      val duplicate = TypeSafeAI.loop[Int, String, Any, Nothing, Int](0)(
        state => Content(state),
        _ => ZIO.succeed(NonEmptyChunk(
          LoopOption.text("same", "a", "first"),
          LoopOption.text("same", "b", "second"),
        )),
      )((state, _) => ZIO.succeed(LoopStep.Done(state)))
      duplicate.run.provideLayer(TypeSafeAIMock()).either.map:
        case Left(_: Error.InvalidLoop) => assertCompletes
        case other                      => assertNever(s"expected InvalidLoop, got $other")
    },
    test("exposes the full probability-bearing turn to the handler") {
      val probabilityAware = TypeSafeAI.loopWithTurn[List[(Int, Double, Double)], String, Any, Nothing, List[(Int, Double, Double)]](Nil)(
        state => Content(state.size),
        _ => ZIO.succeed(NonEmptyChunk(
          LoopOption.text("increment", "increment", "Increase the state."),
          LoopOption.text("finish", "finish", "Finish with observations."),
        )),
      ) { (state, action, turn) =>
        val selectedProbability = turn.answer.probabilities(action).unwrap
        val observed = state :+ (turn.iteration, selectedProbability, turn.answer.confidence.unwrap)
        action match
          case "increment" => ZIO.succeed(LoopStep.Continue(observed))
          case "finish"    => ZIO.succeed(LoopStep.Done(observed))
      }

      probabilityAware.run.provideLayer(TypeSafeAIMock(
        response("increment", 2, 1),
        response("finish", 3, 1),
      )).map: result =>
        assertTrue(
          result.output.map(_._1) == List(1, 2),
          result.output.map(_._2) == List(0.7, 0.3),
          result.output.map(_._3) == List(0.4, 0.4),
          result.usage.inputTokens == 5,
        )
    },
  )
