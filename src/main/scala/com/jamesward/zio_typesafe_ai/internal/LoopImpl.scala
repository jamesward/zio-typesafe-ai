package com.jamesward.zio_typesafe_ai.internal

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import zio.*

private[zio_typesafe_ai] object LoopImpl:

  def run[S, A, R, E, O](
    request: LoopRequest[S, A, R, E, O],
  ): ZIO[Client & R, Error | E, LoopResult[O]] =
    ZIO.serviceWithZIO[Client]: client =>
      def step(
        state: S,
        iteration: Int,
        turns: List[LoopTurn],
        inputTokens: Int,
        outputTokens: Int,
      ): ZIO[R, Error | E, LoopResult[O]] =
        if iteration > request.maxIter then ZIO.fail(Error.MaxIterations(request.maxIter))
        else
          for
            options <- request.options(state)
            ids = options.map(_.id)
            _ <- ZIO.fail(Error.InvalidLoop("option ids must be non-empty"))
              .when(ids.exists(_.isEmpty))
            _ <- ZIO.fail(Error.InvalidLoop("option ids must be unique"))
              .when(ids.distinct.size != ids.size)
            criteria <- ZIO.fromEither(ChoiceCriteria.fromContent(
              options.map(option => option.id -> (option.description: Content | Null)).toMap
            )).mapError(Error.InvalidLoop(_))
            question = Question.Choice(
              "Choose the single action that best advances the current state toward completion. Option ids are opaque; judge their structured descriptions.",
              criteria,
            )
            dynamic = new DynamicSystemOneRequest(
              request.stateView(state),
              request.model,
              List(QuestionId("next_action") -> question),
            )
            jevStarted <- Clock.nanoTime
            response <- dynamic.run.provideEnvironment(ZEnvironment(client))
            jevFinished <- Clock.nanoTime
            latencyMs = (jevFinished - jevStarted) / 1000000L
            answer <- response.answers.get(QuestionId("next_action")) match
              case Some(DynamicAnswer.Choice(value)) => ZIO.succeed(value)
              case Some(other) => ZIO.fail(Error.InvalidLoop(s"expected Choice answer, got $other"))
              case None => ZIO.fail(Error.MissingAnswer(QuestionId("next_action")))
            option <- ZIO.fromOption(options.find(_.id == answer.choice))
              .orElseFail(Error.InvalidLoop(s"Jev selected unknown option '${answer.choice}'"))
            turn = LoopTurn(iteration, answer.choice, answer, response.usage, latencyMs)
            transition <- request.handler(state, option.value)
            result <- transition match
              case LoopStep.Continue(next) =>
                step(
                  next,
                  iteration + 1,
                  turns :+ turn,
                  inputTokens + response.usage.inputTokens,
                  outputTokens + response.usage.outputTokens,
                )
              case LoopStep.Done(output) =>
                ZIO.succeed(LoopResult(
                  output,
                  turns :+ turn,
                  Usage(
                    inputTokens + response.usage.inputTokens,
                    outputTokens + response.usage.outputTokens,
                  ),
                  (turns :+ turn).map(_.latencyMs).sum,
                ))
          yield result

      if request.maxIter <= 0 then ZIO.fail(Error.InvalidLoop("maxIterations must be positive"))
      else step(request.initial, 1, Nil, 0, 0)
