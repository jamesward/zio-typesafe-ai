package com.jamesward.zio_typesafe_ai.orchestration

import zio.*
import zio.json.*
import zio.json.ast.Json

object model:
  enum Expr:
    case Input(path: List[String])
    case Ref(stepId: String, path: List[String] = Nil)
    case At(source: Expr, index: Int, path: List[String] = Nil)
    case Item(path: List[String])
    case Literal(value: Json)
    case Obj(fields: Vector[(String, Expr)])
    case Arr(items: Vector[Expr])

  enum FanOutAuthorization:
    case Unsafe
    case StaticallyBound(maxItems: Int)
    case ReadyFiltered

  enum Step:
    def id: String
    case Call(id: String, operation: String, arguments: Expr.Obj)
    case Construct(id: String, value: Expr)
    case Generate(id: String, operation: String, arguments: Expr.Obj)
    case Filter(id: String, source: Expr, itemSchema: Json.Obj, prompt: String, predicate: Expr)
    case FanOut(id: String, source: Expr, operation: String, arguments: Expr.Obj, authorization: FanOutAuthorization = FanOutAuthorization.Unsafe)

  case class Workflow(steps: Vector[Step], result: Expr):
    def toJson: Json = Json.Obj(
      "steps" -> Json.Arr(steps.map(stepToJson)*),
      "result" -> exprToJson(result),
    )

  case class ExecutionPolicy(
    maxParallelism: Int = 4,
    fanOutLimit: Option[Int] = Some(6),
    maxFilterAttempts: Int = 3,
    maxRecoveries: Int = 3,
  )

  case class ExecutionMetrics(
    operationCalls: Int,
    generativeCalls: Int,
    summedOperationTimeMs: Long,
    operationTimeMs: Long,
    generativeTimeMs: Long,
    executionTimeMs: Long,
    recoveries: Int = 0,
    replans: Int = 0,
  )

  case class ExecutionReport(output: Json, values: Map[String, Json], metrics: ExecutionMetrics)

  sealed trait ExecutionError extends Throwable
  object ExecutionError:
    case class DuplicateStepId(id: String) extends ExecutionError
    case class UnresolvedSteps(ids: List[String]) extends ExecutionError
    case class MissingInput(path: List[String]) extends ExecutionError
    case class MissingReference(stepId: String, path: List[String]) extends ExecutionError
    case class InvalidIndex(index: Int) extends ExecutionError:
      override def getMessage: String = s"Array index must be non-negative, got $index"
    case class IndexOutOfBounds(index: Int, size: Int) extends ExecutionError:
      override def getMessage: String = s"Array index $index is out of bounds for size $size"
    case class MissingAtPath(index: Int, path: List[String]) extends ExecutionError:
      override def getMessage: String = s"Missing object path /${path.mkString("/")} after array index $index"
    case class MissingItem(path: List[String]) extends ExecutionError
    case class ExpectedObject(value: Json) extends ExecutionError
    case class ExpectedArray(value: Json) extends ExecutionError
    case class UnsafeFanOut(message: String) extends ExecutionError:
      override def getMessage: String = message
    case class FanOutTooBroad(actual: Int, guard: Int) extends ExecutionError:
      override def getMessage: String = s"Fan-out source has $actual items, exceeding host guard $guard; explicit filtering is required"
    case class FilterNotReady(evaluation: PredicateFilter.Evaluation) extends ExecutionError:
      override def getMessage: String = s"Filter outcome ${evaluation.classification} (${evaluation.matches.size}/${evaluation.source.size})"
    case class FilterSynthesisRejected(rejection: PredicateFilter.SynthesisRejection) extends ExecutionError:
      override def getMessage: String = s"Filter synthesis exhausted after ${rejection.kind.wireName}: ${rejection.message}"
    case class InvalidPolicy(message: String) extends ExecutionError

  private def exprToJson(expr: Expr): Json = expr match
    case Expr.Input(path) => Json.Obj("input" -> Json.Arr(path.map(Json.Str(_))*))
    case Expr.Ref(id, path) => Json.Obj("ref" -> Json.Str(id), "path" -> Json.Arr(path.map(Json.Str(_))*))
    case Expr.At(source, index, path) => Json.Obj(
      "at" -> exprToJson(source), "index" -> Json.Num(index), "path" -> Json.Arr(path.map(Json.Str(_))*)
    )
    case Expr.Item(path) => Json.Obj("item" -> Json.Arr(path.map(Json.Str(_))*))
    case Expr.Literal(value) => Json.Obj("literal" -> value)
    case Expr.Obj(fields) => Json.Obj("object" -> Json.Obj(fields.map((name, value) => name -> exprToJson(value))*))
    case Expr.Arr(items) => Json.Obj("array" -> Json.Arr(items.map(exprToJson)*))

  private def stepToJson(step: Step): Json = step match
    case Step.Call(id, operation, arguments) => Json.Obj(
      "id" -> Json.Str(id), "kind" -> Json.Str("call"), "operation" -> Json.Str(operation), "arguments" -> exprToJson(arguments)
    )
    case Step.Construct(id, value) => Json.Obj(
      "id" -> Json.Str(id), "kind" -> Json.Str("construct"), "value" -> exprToJson(value)
    )
    case Step.Generate(id, operation, arguments) => Json.Obj(
      "id" -> Json.Str(id), "kind" -> Json.Str("generate"), "operation" -> Json.Str(operation), "arguments" -> exprToJson(arguments)
    )
    case Step.Filter(id, source, itemSchema, _, predicate) => Json.Obj(
      "id" -> Json.Str(id), "kind" -> Json.Str("filter"), "source" -> exprToJson(source),
      "itemSchema" -> itemSchema, "predicate" -> exprToJson(predicate)
    )
    case Step.FanOut(id, source, operation, arguments, authorization) => Json.Obj(
      "id" -> Json.Str(id), "kind" -> Json.Str("fan_out"), "source" -> exprToJson(source),
      "operation" -> Json.Str(operation), "arguments" -> exprToJson(arguments),
      "authorization" -> Json.Str(authorization.toString),
    )

object runtime:
  import model.*
  import model.ExecutionError.*

  trait OperationInvoker:
    def call(operation: String, arguments: Json.Obj): Task[Json]

  trait GenerativeInvoker:
    def generate(operation: String, arguments: Json.Obj): Task[Json]

  object GenerativeInvoker:
    val rejecting: GenerativeInvoker = new GenerativeInvoker:
      def generate(operation: String, arguments: Json.Obj): Task[Json] =
        ZIO.fail(IllegalStateException(s"No generative operation registered for '$operation'"))

  object WorkflowRuntime:
    private case class Timing(calls: Int, summedMs: Long, wallMs: Long)

    def execute(
      workflow: Workflow,
      input: Json,
      operations: OperationInvoker,
      generative: GenerativeInvoker = GenerativeInvoker.rejecting,
      policy: ExecutionPolicy = ExecutionPolicy(),
      initialValues: Map[String, Json] = Map.empty,
    ): IO[Throwable, ExecutionReport] =
      if policy.maxParallelism <= 0 then ZIO.fail(InvalidPolicy("maxParallelism must be positive"))
      else if policy.fanOutLimit.exists(_ <= 0) then ZIO.fail(InvalidPolicy("fanOutLimit must be positive when present"))
      else if policy.maxFilterAttempts <= 0 then ZIO.fail(InvalidPolicy("maxFilterAttempts must be positive"))
      else if policy.maxRecoveries < 0 then ZIO.fail(InvalidPolicy("maxRecoveries must be non-negative"))
      else
        val ids = workflow.steps.map(_.id)
        ids.groupBy(identity).collectFirst { case (id, occurrences) if occurrences.size > 1 => id } match
          case Some(id) => ZIO.fail(DuplicateStepId(id))
          case None =>
            for
              started <- Clock.nanoTime
              operationTiming <- Ref.make(Timing(0, 0L, 0L))
              generativeTiming <- Ref.make(Timing(0, 0L, 0L))
              recoveryCount <- Ref.make(0)
              values <- executeWaves(
                workflow.steps,
                input,
                initialValues,
                operations,
                generative,
                policy,
                operationTiming,
                generativeTiming,
                recoveryCount,
              )
              output <- eval(workflow.result, input, values, None)
              finished <- Clock.nanoTime
              operation <- operationTiming.get
              generation <- generativeTiming.get
              recoveries <- recoveryCount.get
            yield ExecutionReport(
              output,
              values,
              ExecutionMetrics(
                operation.calls,
                generation.calls,
                operation.summedMs,
                operation.wallMs,
                generation.wallMs,
                (finished - started) / 1000000L,
                recoveries,
                0,
              ),
            )

    private def executeWaves(
      pending: Vector[Step],
      input: Json,
      values: Map[String, Json],
      operations: OperationInvoker,
      generative: GenerativeInvoker,
      policy: ExecutionPolicy,
      operationTiming: Ref[Timing],
      generativeTiming: Ref[Timing],
      recoveryCount: Ref[Int],
    ): IO[Throwable, Map[String, Json]] =
      if pending.isEmpty then ZIO.succeed(values)
      else
        val (ready, blocked) = pending.partition(step => dependencies(step).subsetOf(values.keySet))
        if ready.isEmpty then ZIO.fail(UnresolvedSteps(blocked.map(_.id).toList))
        else
          ZIO.foreachPar(ready): step =>
            executeStep(step, input, values, operations, generative, policy, operationTiming, generativeTiming, recoveryCount)
              .map(step.id -> _)
          .withParallelism(policy.maxParallelism)
          .flatMap: completed =>
            executeWaves(
              blocked,
              input,
              values ++ completed,
              operations,
              generative,
              policy,
              operationTiming,
              generativeTiming,
              recoveryCount,
            )

    private def executeStep(
      step: Step,
      input: Json,
      values: Map[String, Json],
      operations: OperationInvoker,
      generative: GenerativeInvoker,
      policy: ExecutionPolicy,
      operationTiming: Ref[Timing],
      generativeTiming: Ref[Timing],
      recoveryCount: Ref[Int],
    ): IO[Throwable, Json] = step match
      case Step.Call(_, operation, arguments) =>
        for
          obj <- evalObject(arguments, input, values, None)
          result <- timedCall(operations, operation, obj, operationTiming, includeWall = true, policy, recoveryCount)
        yield result
      case Step.Construct(_, value) => eval(value, input, values, None)
      case Step.Generate(_, operation, arguments) =>
        for
          obj <- evalObject(arguments, input, values, None)
          started <- Clock.nanoTime
          result <- generative.generate(operation, obj)
          finished <- Clock.nanoTime
          elapsed = (finished - started) / 1000000L
          _ <- generativeTiming.update(timing => Timing(timing.calls + 1, timing.summedMs + elapsed, timing.wallMs + elapsed))
        yield result
      case Step.Filter(_, source, itemSchema, prompt, predicateExpression) =>
        for
          sourceValue <- eval(source, input, values, None)
          items <- ZIO.fromOption(sourceValue.asArray).orElseFail(ExpectedArray(sourceValue))
          generated <- eval(predicateExpression, input, values, None)
          initial <- decodeFilterGeneration(generated, itemSchema, Set.empty)
          guard <- ZIO.fromOption(policy.fanOutLimit).orElseFail(InvalidPolicy("filtering requires a finite host fanOutLimit"))
          checkpoint = FilterCheckpoint(items.toVector, None, None, Set.empty, 0)
          first <- applyFilterGeneration(checkpoint, initial, guard)
          ready <- first match
            case Right(evaluation) => ZIO.succeed(evaluation)
            case Left(pending) => recoverFilter(
              prompt, itemSchema, pending, guard, generative, generativeTiming, policy, recoveryCount,
            )
        yield Json.Arr(ready.matches*)
      case Step.FanOut(_, source, operation, arguments, authorization) =>
        for
          sourceValue <- eval(source, input, values, None)
          items <- ZIO.fromOption(sourceValue.asArray).orElseFail(ExpectedArray(sourceValue))
          _ <- authorization match
            case FanOutAuthorization.Unsafe =>
              ZIO.fail(UnsafeFanOut("Raw array fanout is not authorized; use a Ready filter or a validated declared maxItems bound"))
            case FanOutAuthorization.StaticallyBound(bound) =>
              ZIO.fail(UnsafeFanOut(s"Invalid static fanout proof maxItems=$bound")).when(bound < 0 || policy.fanOutLimit.exists(bound > _)) *>
                ZIO.fail(FanOutTooBroad(items.size, bound)).when(items.size > bound)
            case FanOutAuthorization.ReadyFiltered => ZIO.unit
          _ <- policy.fanOutLimit match
            case Some(guard) => ZIO.fail(FanOutTooBroad(items.size, guard)).when(items.size > guard)
            case None        => ZIO.unit
          groupStarted <- Clock.nanoTime
          results <- ZIO.foreachPar(items): item =>
            for
              obj <- evalObject(arguments, input, values, Some(item))
              result <- timedCall(operations, operation, obj, operationTiming, includeWall = false, policy, recoveryCount)
            yield result
          .withParallelism(policy.maxParallelism)
          groupFinished <- Clock.nanoTime
          _ <- operationTiming.update(timing => timing.copy(wallMs = timing.wallMs + (groupFinished - groupStarted) / 1000000L))
        yield Json.Arr(results)

    private case class FilterCheckpoint(
      parentSource: Vector[Json],
      basis: Option[PredicateFilter.Evaluation],
      synthesisRejection: Option[PredicateFilter.SynthesisRejection],
      seenPredicates: Set[String],
      attemptsUsed: Int,
    )

    private enum FilterGeneration:
      case Accepted(predicate: PredicateFilter.Predicate, canonical: Json.Obj)
      case Rejected(rejection: PredicateFilter.SynthesisRejection)

    private def decodeFilterGeneration(
      generated: Json,
      itemSchema: Json.Obj,
      seenPredicates: Set[String],
    ): IO[Throwable, FilterGeneration] =
      ZIO.fromEither(PredicateFilter.SynthesisResult.fromGenerativeValue(generated)).flatMap:
        case PredicateFilter.SynthesisResult.Rejected(rejection) =>
          ZIO.succeed(FilterGeneration.Rejected(rejection))
        case PredicateFilter.SynthesisResult.Accepted(criteria) =>
          ZIO.fromEither(PredicateFilter.parseAndValidate(criteria, itemSchema)).map: predicate =>
            val canonical = PredicateFilter.canonical(predicate)
            if seenPredicates.contains(canonical.toJson) then
              FilterGeneration.Rejected(PredicateFilter.SynthesisRejection(
                PredicateFilter.SynthesisRejectionKind.RepeatedPredicate,
                "Predicate duplicated a previously accepted canonical predicate",
              ))
            else FilterGeneration.Accepted(predicate, canonical)

    private def applyFilterGeneration(
      checkpoint: FilterCheckpoint,
      generation: FilterGeneration,
      guard: Int,
    ): IO[Throwable, Either[FilterCheckpoint, PredicateFilter.Evaluation]] = generation match
      case FilterGeneration.Rejected(rejection) =>
        ZIO.succeed(Left(checkpoint.copy(
          synthesisRejection = Some(rejection),
          attemptsUsed = checkpoint.attemptsUsed + 1,
        )))
      case FilterGeneration.Accepted(predicate, canonical) =>
        val source = checkpoint.basis match
          case Some(evaluation) if evaluation.classification == PredicateFilter.Classification.TooBroad => evaluation.matches
          case _ => checkpoint.parentSource
        val priorCount = checkpoint.basis.collect:
          case evaluation if evaluation.classification == PredicateFilter.Classification.TooBroad => evaluation.matches.size
        ZIO.fromEither(PredicateFilter.classify(source, predicate, guard, priorCount)).map: evaluation =>
          if evaluation.classification == PredicateFilter.Classification.Ready then Right(evaluation)
          else Left(checkpoint.copy(
            parentSource = source,
            basis = Some(evaluation),
            synthesisRejection = None,
            seenPredicates = checkpoint.seenPredicates + canonical.toJson,
            attemptsUsed = checkpoint.attemptsUsed + 1,
          ))

    private def recoverFilter(
      prompt: String,
      itemSchema: Json.Obj,
      checkpoint: FilterCheckpoint,
      guard: Int,
      generative: GenerativeInvoker,
      timing: Ref[Timing],
      policy: ExecutionPolicy,
      recoveryCount: Ref[Int],
    ): Task[PredicateFilter.Evaluation] =
      def exhausted: Task[PredicateFilter.Evaluation] = checkpoint.basis match
        case Some(evaluation) => ZIO.fail(FilterNotReady(evaluation))
        case None => ZIO.fail(FilterSynthesisRejected(checkpoint.synthesisRejection.getOrElse(
          PredicateFilter.SynthesisRejection(
            PredicateFilter.SynthesisRejectionKind.InvalidPredicate,
            "Filter synthesis produced no valid evaluation",
          )
        )))

      if checkpoint.attemptsUsed >= policy.maxFilterAttempts then exhausted
      else
        recoveryCount.modify: used =>
          if used < policy.maxRecoveries then true -> (used + 1) else false -> used
        .flatMap: canRecover =>
          if !canRecover then exhausted
          else
            val diagnostic = checkpoint.basis.map(filterDiagnostic)
            val evidenceSource = checkpoint.basis match
              case Some(evaluation) if evaluation.classification == PredicateFilter.Classification.TooBroad => evaluation.matches
              case _ => checkpoint.parentSource
            val prepared = JevVariantFilter.prepare(evidenceSource, itemSchema)
            val evidence = JevVariantFilter.llmEvidence(prompt, evidenceSource.size, prepared)
            val arguments = PredicateFilter.SynthesisRequest(
              prompt,
              itemSchema,
              checkpoint.basis.map(_.canonicalPredicate),
              diagnostic,
              checkpoint.synthesisRejection,
              Some(evidence),
            ).toJson
            for
              started <- Clock.nanoTime
              generated <- generative.generate(Catalog.FilterName, arguments)
              finished <- Clock.nanoTime
              elapsed = (finished - started) / 1000000L
              _ <- timing.update(t => Timing(t.calls + 1, t.summedMs + elapsed, t.wallMs + elapsed))
              decoded <- decodeFilterGeneration(generated, itemSchema, checkpoint.seenPredicates)
              next <- applyFilterGeneration(checkpoint, decoded, guard)
              ready <- next match
                case Right(evaluation) => ZIO.succeed(evaluation)
                case Left(pending) => recoverFilter(prompt, itemSchema, pending, guard, generative, timing, policy, recoveryCount)
            yield ready

    private def filterDiagnostic(evaluation: PredicateFilter.Evaluation): PredicateFilter.Diagnostic =
      PredicateFilter.Diagnostic(
        evaluation.classification.toString,
        evaluation.source.size,
        evaluation.matches.size,
        evaluation.classification match
          case PredicateFilter.Classification.TooBroad => "Refine over the complete prior match set; the new predicate must strictly reduce it."
          case PredicateFilter.Classification.NoMatches => "Replace the predicate over the retained parent source."
          case PredicateFilter.Classification.NoProgress => "Replace the predicate over the retained complete source."
          case PredicateFilter.Classification.Ready => "ready",
      )

    private def timedCall(
      operations: OperationInvoker,
      operation: String,
      arguments: Json.Obj,
      timingRef: Ref[Timing],
      includeWall: Boolean,
      policy: ExecutionPolicy,
      recoveryCount: Ref[Int],
    ): Task[Json] =
      def attempt: Task[Json] =
        for
          started <- Clock.nanoTime
          result <- operations.call(operation, arguments)
          finished <- Clock.nanoTime
          elapsed = (finished - started) / 1000000L
          _ <- timingRef.update: timing =>
            Timing(
              timing.calls + 1,
              timing.summedMs + elapsed,
              timing.wallMs + (if includeWall then elapsed else 0L),
            )
        yield result
      def loop: Task[Json] = attempt.catchAll: error =>
        recoveryCount.modify: used =>
          if used < policy.maxRecoveries then true -> (used + 1) else false -> used
        .flatMap(retry => if retry then loop else ZIO.fail(error))
      loop

    private def dependencies(step: Step): Set[String] = step match
      case Step.Call(_, _, arguments)          => dependencies(arguments)
      case Step.Construct(_, value)            => dependencies(value)
      case Step.Generate(_, _, arguments)      => dependencies(arguments)
      case Step.Filter(_, source, _, _, predicate) => dependencies(source) ++ dependencies(predicate)
      case Step.FanOut(_, source, _, arguments, _) => dependencies(source) ++ dependencies(arguments)

    private def dependencies(expr: Expr): Set[String] = expr match
      case Expr.Input(_)       => Set.empty
      case Expr.Ref(id, _)     => Set(id)
      case Expr.At(source, _, _) => dependencies(source)
      case Expr.Item(_)        => Set.empty
      case Expr.Literal(_)     => Set.empty
      case Expr.Obj(fields)    => fields.iterator.flatMap((_, value) => dependencies(value)).toSet
      case Expr.Arr(items)     => items.iterator.flatMap(dependencies).toSet

    private def evalObject(
      expression: Expr.Obj,
      input: Json,
      values: Map[String, Json],
      item: Option[Json],
    ): IO[ExecutionError, Json.Obj] =
      eval(expression, input, values, item).flatMap(json => ZIO.fromOption(json.asObject).orElseFail(ExpectedObject(json)))

    private def eval(
      expr: Expr,
      input: Json,
      values: Map[String, Json],
      item: Option[Json],
    ): IO[ExecutionError, Json] = expr match
      case Expr.Input(path) => descend(input, path).mapError(_ => MissingInput(path))
      case Expr.Ref(stepId, path) =>
        ZIO.fromOption(values.get(stepId)).orElseFail(MissingReference(stepId, path))
          .flatMap(value => descend(value, path).mapError(_ => MissingReference(stepId, path)))
      case Expr.At(source, index, path) =>
        for
          _ <- ZIO.fail(InvalidIndex(index)).when(index < 0)
          sourceValue <- eval(source, input, values, item)
          items <- ZIO.fromOption(sourceValue.asArray).orElseFail(ExpectedArray(sourceValue))
          selected <- ZIO.fromOption(items.lift(index)).orElseFail(IndexOutOfBounds(index, items.size))
          projected <- descend(selected, path).mapError(_ => MissingAtPath(index, path))
        yield projected
      case Expr.Item(path) =>
        ZIO.fromOption(item).orElseFail(MissingItem(path))
          .flatMap(value => descend(value, path).mapError(_ => MissingItem(path)))
      case Expr.Literal(value) => ZIO.succeed(value)
      case Expr.Obj(fields) =>
        ZIO.foreach(fields) { case (name, value) => eval(value, input, values, item).map(name -> _) }
          .map(entries => Json.Obj(Chunk.fromIterable(entries)))
      case Expr.Arr(items) =>
        ZIO.foreach(items)(eval(_, input, values, item)).map(values => Json.Arr(Chunk.fromIterable(values)))

    private def descend(value: Json, path: List[String]): IO[Unit, Json] =
      path.foldLeft[IO[Unit, Json]](ZIO.succeed(value)): (current, field) =>
        current.flatMap(json => ZIO.fromOption(json.asObject.flatMap(_.get(field))).orElseFail(()))
