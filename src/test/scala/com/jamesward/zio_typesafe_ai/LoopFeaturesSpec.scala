package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.internal.Wire
import zio.*
import zio.json.ast.Json
import zio.schema.{Schema, derived}
import zio.test.*

object LoopFeaturesSpec extends ZIOSpecDefault:

  private case class StructuredInstructions(goal: String, rules: List[String]) derives Schema
  private enum HostFailure:
    case Boom

  private def response(choice: String, input: Int = 1, output: Int = 1) =
    TypeSafeAIMock.MockBehavior.Respond(
      Map("next_action" -> Wire.Answer.Choice(
        choice,
        Map("increment" -> 0.7, "finish" -> 0.3),
        0.4,
      )),
      Wire.Usage(input, output),
    )

  private def base(
    optionCount: Ref[Int] | Null = null,
    handlerCount: Ref[Int] | Null = null,
  ) =
    TypeSafeAI.loop[Int, String, Any, Nothing, Int](0)(
      state => Content(state),
      _ =>
        val counted = optionCount match
          case null => ZIO.unit
          case ref: Ref[Int] => ref.update(_ + 1)
        counted.as(NonEmptyChunk(
          LoopOption.text("increment", "increment", "Increase the state by one."),
          LoopOption.text("finish", "finish", "Return the current state."),
        )),
    ) { (state, action) =>
      val counted = handlerCount match
        case null => ZIO.unit
        case ref: Ref[Int] => ref.update(_ + 1)
      counted.as(action match
        case "increment" => LoopStep.Continue(state + 1)
        case "finish"    => LoopStep.Done(state)
      )
    }

  private def instruction(request: Wire.SystemOneRequest): Json =
    request.body.asObject
      .flatMap(_.get("questions"))
      .flatMap(_.asObject)
      .flatMap(_.get("next_action"))
      .flatMap(_.asObject)
      .flatMap(_.get("instructions"))
      .get

  def spec = suite("loop observers, retries, instructions, and audit")(
    suite("choice instructions")(
      test("freezes the exact default request shape") {
        for
          tracked <- TypeSafeAIMock.tracked(response("finish"))
          _ <- base().run.provideLayer(tracked.layer)
          requests <- tracked.requests
        yield assertTrue(requests.head.body.equals(Json.Obj(
          "state" -> Json.Num(0),
          "model" -> Json.Str("mock"),
          "questions" -> Json.Obj(
            "next_action" -> Json.Obj(
              "type" -> Json.Str("choice"),
              "instructions" -> Json.Str("Choose the single action that best advances the current state toward completion. Option ids are opaque; judge their structured descriptions."),
              "criteria" -> Json.Obj(
                "increment" -> Json.Str("Increase the state by one."),
                "finish" -> Json.Str("Return the current state."),
              ),
            ),
          ),
        )))
      },
      test("supports custom string and structured instruction wire shapes") {
        for
          stringMock <- TypeSafeAIMock.tracked(response("finish"))
          structuredMock <- TypeSafeAIMock.tracked(response("finish"))
          _ <- base().choiceInstructions("Use the safest legal transition.").run.provideLayer(stringMock.layer)
          structured = StructuredInstructions("finish safely", List("prefer completion", "avoid mutation"))
          _ <- base().choiceInstructions(structured).run.provideLayer(structuredMock.layer)
          stringRequests <- stringMock.requests
          structuredRequests <- structuredMock.requests
        yield assertTrue(
          instruction(stringRequests.head).equals(Json.Str("Use the safest legal transition.")),
          instruction(structuredRequests.head).equals(Json.Obj(
            "goal" -> Json.Str("finish safely"),
            "rules" -> Json.Arr(Json.Str("prefer completion"), Json.Str("avoid mutation")),
          )),
        )
      },
      test("copy-style methods preserve every setting") {
        val instructions = StructuredInstructions("finish", List("safe"))
        for
          tracked <- TypeSafeAIMock.tracked(response("finish"))
          events <- Ref.make(List.empty[String])
          observer = LoopObserver.make[Nothing, Int] {
            case LoopObservation.Completion(_) => events.update(_ :+ "complete")
            case _                             => ZIO.unit
          }
          result <- base()
            .choiceInstructions(instructions)
            .retryEachTurn(LoopRetryPolicy(2, Duration.Zero))
            .observe(observer)
            .model(ModelId("special"))
            .maxIterations(3)
            .run
            .provideLayer(tracked.layer)
          requests <- tracked.requests
          seen <- events.get
        yield assertTrue(
          result.output == 0,
          requests.head.body.asObject.flatMap(_.get("model")).contains(Json.Str("special")),
          instruction(requests.head).isInstanceOf[Json.Obj],
          seen == List("complete"),
        )
      },
    ),
    suite("semantic observation")(
      test("orders options, decision, turn-aware handler, recursion, and completion") {
        for
          events <- Ref.make(List.empty[String])
          observer = LoopObserver.make[Nothing, Int] {
            case LoopObservation.OptionsGenerated(iteration, _, _) => events.update(_ :+ s"options-$iteration")
            case LoopObservation.Decision(turn)                    => events.update(_ :+ s"decision-${turn.iteration}")
            case LoopObservation.Completion(_)                     => events.update(_ :+ "completion")
            case LoopObservation.Failure(_, _)                     => events.update(_ :+ "failure")
          }
          request = TypeSafeAI.loopWithTurn[Int, String, Any, Nothing, Int](0)(
            state => Content(state),
            _ => ZIO.succeed(NonEmptyChunk(
              LoopOption.text("increment", "increment", "Increase."),
              LoopOption.text("finish", "finish", "Finish."),
            )),
          ) { (state, action, turn) =>
            events.update(_ :+ s"handler-${turn.iteration}").as(
              if action == "increment" then LoopStep.Continue(state + 1) else LoopStep.Done(state)
            )
          }.observe(observer)
          _ <- request.run.provideLayer(TypeSafeAIMock(response("increment"), response("finish")))
          seen <- events.get
        yield assertTrue(seen == List(
          "options-1", "decision-1", "handler-1",
          "options-2", "decision-2", "handler-2", "completion",
        ))
      },
      test("reports one terminal host error with accumulated turns") {
        for
          failures <- Ref.make(List.empty[(Cause[Error | HostFailure], List[LoopTurn])])
          observer = LoopObserver.make[HostFailure, Int] {
            case LoopObservation.Failure(cause, turns) => failures.update(_ :+ (cause -> turns))
            case _                                     => ZIO.unit
          }
          request = TypeSafeAI.loop[Int, String, Any, HostFailure, Int](0)(
            state => Content(state),
            state => if state == 0 then ZIO.succeed(NonEmptyChunk(LoopOption.text("increment", "increment", "Increase.")))
                     else ZIO.fail(HostFailure.Boom),
          )((state, _) => ZIO.succeed(LoopStep.Continue(state + 1))).observe(observer)
          exit <- request.run.provideLayer(TypeSafeAIMock(response("increment"))).exit
          seen <- failures.get
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).contains(HostFailure.Boom),
          seen.size == 1,
          seen.head._1.failureOption.contains(HostFailure.Boom),
          seen.head._2.map(_.choice) == List("increment"),
        )
      },
      test("reports handler defects once with the successful decision") {
        for
          failures <- Ref.make(List.empty[(Cause[Error], List[LoopTurn])])
          observer = LoopObserver.make[Nothing, Int] {
            case LoopObservation.Failure(cause, turns) => failures.update(_ :+ (cause -> turns))
            case _                                     => ZIO.unit
          }
          request = TypeSafeAI.loop[Int, String, Any, Nothing, Int](0)(
            state => Content(state),
            _ => ZIO.succeed(NonEmptyChunk(LoopOption.text("finish", "finish", "Finish."))),
          )((_, _) => ZIO.dieMessage("handler defect")).observe(observer)
          exit <- request.run.provideLayer(TypeSafeAIMock(response("finish"))).exit
          seen <- failures.get
        yield assertTrue(
          exit.causeOption.exists(_.defects.exists(_.getMessage == "handler defect")),
          seen.size == 1,
          seen.head._1.defects.exists(_.getMessage == "handler defect"),
          seen.head._2.map(_.choice) == List("finish"),
        )
      },
      test("reports max-iteration failure once without recursive duplicates") {
        for
          failures <- Ref.make(List.empty[List[LoopTurn]])
          observer = LoopObserver.make[Nothing, Int] {
            case LoopObservation.Failure(_, turns) => failures.update(_ :+ turns)
            case _                                 => ZIO.unit
          }
          exit <- base().maxIterations(1).observe(observer).run
            .provideLayer(TypeSafeAIMock(response("increment"))).exit
          seen <- failures.get
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Error.MaxIterations]),
          seen.size == 1,
          seen.head.map(_.iteration) == List(1),
        )
      },
      test("observer defects and self-interruption are isolated") {
        val defecting = LoopObserver.make[Nothing, Int](_ => ZIO.dieMessage("observer defect"))
        val interrupting = LoopObserver.make[Nothing, Int](_ => ZIO.interrupt)
        for
          first <- base().observe(defecting).run.provideLayer(TypeSafeAIMock(response("finish")))
          second <- base().observe(interrupting).run.provideLayer(TypeSafeAIMock(response("finish")))
        yield assertTrue(first.output == 0, second.output == 0)
      },
    ),
    suite("per-turn retries")(
      test("default performs no retry") {
        for
          tracked <- TypeSafeAIMock.tracked(TypeSafeAIMock.MockBehavior.Fail(Error.RateLimit("busy")), response("finish"))
          exit <- base().run.provideLayer(tracked.layer).exit
          count <- tracked.requestCount
        yield assertTrue(exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Error.RateLimit]), count == 1)
      },
      test("retryable failures succeed without replaying options or handler") {
        for
          optionCount <- Ref.make(0)
          handlerCount <- Ref.make(0)
          tracked <- TypeSafeAIMock.tracked(
            TypeSafeAIMock.MockBehavior.Fail(Error.RateLimit("busy")),
            TypeSafeAIMock.MockBehavior.Fail(Error.ServiceOverloaded("busy")),
            response("finish", 5, 2),
          )
          result <- base(optionCount, handlerCount)
            .retryEachTurn(LoopRetryPolicy(2, Duration.Zero))
            .run.provideLayer(tracked.layer)
          attempts <- tracked.requestCount
          options <- optionCount.get
          handlers <- handlerCount.get
        yield assertTrue(
          result.output == 0,
          attempts == 3,
          options == 1,
          handlers == 1,
          result.turns.size == 1,
          result.usage.equals(Usage(5, 2)),
        )
      },
      test("does not retry non-retryable failures") {
        for
          tracked <- TypeSafeAIMock.tracked(
            TypeSafeAIMock.MockBehavior.Fail(Error.BadRequest("bad")),
            response("finish"),
          )
          _ <- base().retryEachTurn(LoopRetryPolicy(3, Duration.Zero)).run.provideLayer(tracked.layer).exit
          count <- tracked.requestCount
        yield assertTrue(count == 1)
      },
      test("exhausts exactly maxRetries additional attempts") {
        for
          tracked <- TypeSafeAIMock.tracked(
            TypeSafeAIMock.MockBehavior.Fail(Error.InternalServer(zio.http.Status.InternalServerError, "one")),
            TypeSafeAIMock.MockBehavior.Fail(Error.RateLimit("two")),
            TypeSafeAIMock.MockBehavior.Fail(Error.ServiceOverloaded("three")),
            response("finish"),
          )
          exit <- base().retryEachTurn(LoopRetryPolicy(2, Duration.Zero)).run.provideLayer(tracked.layer).exit
          count <- tracked.requestCount
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Error.ServiceOverloaded]),
          count == 3,
        )
      },
      test("physical exchange attempts remain distinct from one logical decision") {
        for
          exchanges <- Ref.make(0)
          decisions <- Ref.make(0)
          tracked <- TypeSafeAIMock.tracked(
            TypeSafeAIMock.MockBehavior.Fail(Error.RateLimit("busy")),
            response("finish"),
          )
          exchangeObserver = ExchangeObserver.fromFunction(_ => exchanges.update(_ + 1))
          loopObserver = LoopObserver.make[Nothing, Int] {
            case LoopObservation.Decision(_) => decisions.update(_ + 1)
            case _                           => ZIO.unit
          }
          result <- base().retryEachTurn(LoopRetryPolicy(1, Duration.Zero)).observe(loopObserver).run
            .provideLayer(tracked.layer >>> Client.observed(exchangeObserver))
          exchangeCount <- exchanges.get
          decisionCount <- decisions.get
        yield assertTrue(result.turns.size == 1, exchangeCount == 2, decisionCount == 1)
      },
      test("invalid retry policy fails as InvalidLoop before options or exchange") {
        for
          optionCount <- Ref.make(0)
          tracked <- TypeSafeAIMock.tracked(response("finish"))
          exit <- base(optionCount = optionCount)
            .retryEachTurn(LoopRetryPolicy(-1, Duration.Zero)).run.provideLayer(tracked.layer).exit
          options <- optionCount.get
          requests <- tracked.requestCount
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[Error.InvalidLoop]),
          options == 0,
          requests == 0,
        )
      },
    ),
    suite("opt-in audit")(
      test("captures one Content-only snapshot per decision and delegates result accessors") {
        for
          result <- base().runAudited.provideLayer(TypeSafeAIMock(
            response("increment", 2, 1),
            response("finish", 3, 1),
          ))
        yield assertTrue(
          result.output == 1,
          result.turns.map(_.choice) == List("increment", "finish"),
          result.usage.equals(Usage(5, 2)),
          result.latencyMs == result.result.latencyMs,
          result.audit.map(_.iteration) == List(1, 2),
          result.audit.flatMap(_.stateView.as[Int].toOption) == List(0, 1),
          result.audit.forall(_.options.map(_.id) == List("increment", "finish")),
          result.audit.head.options.head.description.as[String].contains("Increase the state by one."),
        )
      },
      test("ordinary run still returns the unchanged LoopResult shape") {
        base().run.provideLayer(TypeSafeAIMock(response("finish"))).map: result =>
          val unchanged: LoopResult[Int] = result
          assertTrue(unchanged.output == 0, unchanged.turns.size == 1)
      },
    ),
  )
