package com.jamesward.zio_typesafe_ai.internal

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import zio.*
import zio.schema.codec.json.schemaJson

private[zio_typesafe_ai] object LoopImpl:

  def run[S, A, R, E, O](
    request: LoopRequest[S, A, R, E, O],
  ): ZIO[Client & R, Error | E, LoopResult[O]] =
    execute(request, collectAudit = false).map(_._1)

  def runAudited[S, A, R, E, O](
    request: LoopRequest[S, A, R, E, O],
  ): ZIO[Client & R, Error | E, AuditedLoopResult[O]] =
    execute(request, collectAudit = true).map((result, audit) => AuditedLoopResult(result, audit))

  private def execute[S, A, R, E, O](
    request: LoopRequest[S, A, R, E, O],
    collectAudit: Boolean,
  ): ZIO[Client & R, Error | E, (LoopResult[O], List[LoopAuditTurn])] =
    ZIO.serviceWithZIO[Client]: client =>
      Ref.make(List.empty[LoopTurn]).flatMap: completedTurns =>
        def notify(observation: => LoopObservation[E, O]): UIO[Unit] =
          TypeSafeAI.notifyBestEffort("TypeSafe AI loop"):
            ZIO.suspendSucceed(request.observer.observe(observation))

        def runJev(dynamic: DynamicSystemOneRequest): IO[Error, Result[Map[QuestionId, DynamicAnswer]]] =
          val once = dynamic.run.provideEnvironment(ZEnvironment(client))
          if request.retryPolicy.maxRetries == 0 then once
          else
            once.retry(
              Schedule.recurWhile[Error]:
                case _: (Error.RateLimit | Error.ServiceOverloaded | Error.InternalServer) => true
                case _                                                                     => false
              && Schedule.exponential(request.retryPolicy.initialDelay)
              && Schedule.recurs(request.retryPolicy.maxRetries)
            )

        def step(
          state: S,
          iteration: Int,
          turns: List[LoopTurn],
          audit: List[LoopAuditTurn],
          inputTokens: Int,
          outputTokens: Int,
        ): ZIO[R, Error | E, (LoopResult[O], List[LoopAuditTurn])] =
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
              stateView = request.stateView(state)
              auditOptions = options.toList.map(option => LoopAuditOption(option.id, option.description))
              _ <- notify(LoopObservation.OptionsGenerated(iteration, stateView, auditOptions))
              question = Question.Choice(request.instructions.json, criteria)
              dynamic = new DynamicSystemOneRequest(
                stateView,
                request.model,
                List(QuestionId("next_action") -> question),
              )
              jevStarted <- Clock.nanoTime
              response <- runJev(dynamic)
              jevFinished <- Clock.nanoTime
              latencyMs = (jevFinished - jevStarted) / 1000000L
              answer <- response.answers.get(QuestionId("next_action")) match
                case Some(DynamicAnswer.Choice(value)) => ZIO.succeed(value)
                case Some(other) => ZIO.fail(Error.InvalidLoop(s"expected Choice answer, got $other"))
                case None => ZIO.fail(Error.MissingAnswer(QuestionId("next_action")))
              option <- ZIO.fromOption(options.find(_.id == answer.choice))
                .orElseFail(Error.InvalidLoop(s"Jev selected unknown option '${answer.choice}'"))
              turn = LoopTurn(iteration, answer.choice, answer, response.usage, latencyMs)
              _ <- completedTurns.update(_ :+ turn)
              nextAudit =
                if collectAudit then audit :+ LoopAuditTurn(iteration, stateView, auditOptions)
                else audit
              _ <- notify(LoopObservation.Decision(turn))
              transition <- request.handler(state, option.value, turn)
              result <- transition match
                case LoopStep.Continue(next) =>
                  step(
                    next,
                    iteration + 1,
                    turns :+ turn,
                    nextAudit,
                    inputTokens + response.usage.inputTokens,
                    outputTokens + response.usage.outputTokens,
                  )
                case LoopStep.Done(output) =>
                  val allTurns = turns :+ turn
                  ZIO.succeed((
                    LoopResult(
                      output,
                      allTurns,
                      Usage(
                        inputTokens + response.usage.inputTokens,
                        outputTokens + response.usage.outputTokens,
                      ),
                      allTurns.map(_.latencyMs).sum,
                    ),
                    nextAudit,
                  ))
            yield result

        val validate =
          if request.maxIter <= 0 then
            ZIO.fail(Error.InvalidLoop("maxIterations must be positive"))
          else if request.retryPolicy.maxRetries < 0 then
            ZIO.fail(Error.InvalidLoop("retry maxRetries must be non-negative"))
          else if request.retryPolicy.initialDelay < Duration.Zero || request.retryPolicy.initialDelay >= Duration.Infinity then
            ZIO.fail(Error.InvalidLoop("retry initialDelay must be finite and non-negative"))
          else ZIO.unit

        (validate *> step(request.initial, 1, Nil, Nil, 0, 0)).foldCauseZIO(
          cause =>
            completedTurns.get.flatMap: turns =>
              notify(LoopObservation.Failure(cause, turns)) *>
                ZIO.refailCause(cause),
          resultAndAudit =>
            notify(LoopObservation.Completion(resultAndAudit._1)).as(resultAndAudit),
        )
