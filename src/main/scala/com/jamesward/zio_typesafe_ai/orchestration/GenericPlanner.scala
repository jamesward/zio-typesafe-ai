package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.orchestration.SchemaModel.SchemaPath
import com.jamesward.zio_typesafe_ai.orchestration.model.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.codec.json.schemaJson

enum ValueOrigin:
  case InitialInput
  case ToolOutput(stepId: String, operation: String)
  case ExtractedArgument(stepId: String, targetOperation: String)
  case Filtered(stepId: String, parentOrigin: String)

enum InvocationMode:
  case Direct, FanOut

enum FanOutSafety:
  case Unsafe, StaticBound, ReadyFiltered

case class AvailableValue(
  expr: Expr,
  schema: Json,
  exposedName: String,
  path: SchemaPath,
  origin: ValueOrigin,
  ordinal: Int,
  isArray: Boolean = false,
  fanOutSafety: FanOutSafety = FanOutSafety.Unsafe,
)

case class EvidenceRef(stepId: String, operation: String, expr: Expr, producerStepId: String)

case class InvocationFingerprint(
  operation: String,
  mode: InvocationMode,
  source: Option[String],
  bindings: Vector[(String, String)],
  missing: Vector[String],
)

case class OrchestrationConfig(
  maxOperations: Int = 8,
  maxCallsPerOperation: Int = 1,
  maxParallelism: Int = 4,
  hostGuard: Int = 12,
  maxFilterAttempts: Int = 4,
  maxRecoveries: Int = 3,
  jevFilterVariantThreshold: Int = 255,
  maxIterations: Int = 12,
  jevTurnRetries: Int = 0,
):
  def fanOutLimit: Int = hostGuard

case class PlanState(
  prompt: String,
  catalog: Vector[OperationSpec],
  steps: Vector[Step] = Vector.empty,
  available: Vector[AvailableValue] = Vector.empty,
  evidence: Vector[EvidenceRef] = Vector.empty,
  attempted: Set[InvocationFingerprint] = Set.empty,
  attemptedFilters: Set[String] = Set.empty,
  operationCounts: Map[String, Int] = Map.empty,
  summaryStep: Option[String] = None,
  nextOrdinal: Int = 1,
)

case class InvocationPlan(
  operation: OperationSpec,
  mode: InvocationMode,
  source: Option[AvailableValue],
  bindings: Vector[(String, Expr, String)],
  missing: Vector[String],
  projectedSchema: Option[Json.Obj],
  addedSteps: Vector[Step],
  addedAvailable: Vector[AvailableValue],
  evidence: EvidenceRef,
  fingerprint: InvocationFingerprint,
  nextOrdinal: Int,
)

case class FilterPlan(
  source: AvailableValue,
  itemSchema: Json.Obj,
  addedSteps: Vector[Step],
  addedAvailable: AvailableValue,
  evidence: EvidenceRef,
  fingerprint: String,
  nextOrdinal: Int,
)

enum Transition:
  case Invoke(plan: InvocationPlan)
  case ApplyFilter(plan: FilterPlan)
  case AddSummary(step: Step.Generate)
  case Finish(summaryStepId: String)

case class Candidate(id: String, view: Json.Obj, transition: Transition, sortKey: String)

object GenericPlanner:
  sealed trait PlanningError extends Throwable
  case class NoValidTransitions(message: String) extends PlanningError:
    override def getMessage: String = message
  case class TooManyTransitions(count: Int) extends PlanningError:
    override def getMessage: String = s"Jev supports at most 255 transitions, generated $count; reduce the active operation catalog"

  def initial(prompt: String, catalog: Vector[OperationSpec]): PlanState = PlanState(prompt, catalog)

  def initial(
    prompt: String,
    catalog: Vector[OperationSpec],
    input: Json.Obj,
    inputSchema: Json.Obj,
  ): PlanState =
    val available = SchemaModel.indexOutput(inputSchema).filter: indexed =>
      indexed.path.fields.foldLeft(Option(input: Json))((value, field) =>
        value.flatMap(_.asObject).flatMap(_.get(field))
      ).nonEmpty
    .zipWithIndex.map: (indexed, index) =>
      AvailableValue(
        Expr.Input(indexed.path.fields.toList),
        indexed.schema,
        indexed.exposedName,
        indexed.path,
        ValueOrigin.InitialInput,
        index + 1,
        indexed.isArray,
      )
    PlanState(prompt, catalog, available = available, nextOrdinal = available.size + 1)

  def candidates(state: PlanState, config: OrchestrationConfig = OrchestrationConfig()): IO[PlanningError, Vector[Candidate]] =
    val raw =
      if state.summaryStep.nonEmpty then Vector(finishCandidate(state))
      else
        val invocationCount = state.operationCounts.values.sum
        val invocationCandidates =
          if invocationCount >= config.maxOperations then Vector.empty
          else state.catalog.filter(_.kind == CapabilityKind.Mcp).sortBy(_.name).flatMap: operation =>
            if state.operationCounts.getOrElse(operation.name, 0) >= config.maxCallsPerOperation then Vector.empty
            else directPlan(state, operation).toVector ++ fanOutPlans(state, operation, config)
        val filters = filterCandidates(state)
        def consumed(source: AvailableValue): Boolean =
          val sourceKey = originKey(source)
          state.attempted.exists(fingerprint =>
            fingerprint.source.contains(sourceKey) || fingerprint.bindings.exists((_, bindingOrigin) =>
              bindingOrigin == sourceKey || bindingOrigin.startsWith(sourceKey + "/")
            )
          )
        val latestReadySource = state.available.filter(_.fanOutSafety == FanOutSafety.ReadyFiltered)
          .sortBy(_.ordinal).lastOption
        val hasUnconsumedReadyFanOut = latestReadySource.exists: latest =>
          !consumed(latest) && invocationCandidates.exists:
            case Candidate(_, _, Transition.Invoke(plan), _) =>
              plan.mode == InvocationMode.FanOut && plan.source.exists(source => originKey(source) == originKey(latest))
            case _ => false
        val summary =
          if state.evidence.nonEmpty && !hasUnconsumedReadyFanOut then Vector(summaryCandidate(state))
          else Vector.empty
        invocationCandidates ++ filters ++ summary
    val filtered = raw.filter:
      case Candidate(_, _, Transition.Invoke(plan), _)      => !state.attempted.contains(plan.fingerprint)
      case Candidate(_, _, Transition.ApplyFilter(plan), _) => !state.attemptedFilters.contains(plan.fingerprint)
      case _                                                => true
    val sorted = filtered.sortBy(_.sortKey).zipWithIndex.map: (candidate, index) =>
      val id = f"c$index%03d"
      candidate.copy(id = id, view = Json.Obj(candidate.view.fields :+ ("candidateId" -> Json.Str(id))))
    if sorted.isEmpty then ZIO.fail(NoValidTransitions("No schema-safe operation or summary transition is available"))
    else if sorted.size > 255 then ZIO.fail(TooManyTransitions(sorted.size))
    else ZIO.succeed(sorted)

  def applyTransition(state: PlanState, transition: Transition): Either[PlanningError, PlanState | Workflow] = transition match
    case Transition.Invoke(plan) =>
      Right(state.copy(
        steps = state.steps ++ plan.addedSteps,
        available = state.available ++ plan.addedAvailable,
        evidence = state.evidence :+ plan.evidence,
        attempted = state.attempted + plan.fingerprint,
        operationCounts = state.operationCounts.updated(plan.operation.name, state.operationCounts.getOrElse(plan.operation.name, 0) + 1),
        nextOrdinal = plan.nextOrdinal,
      ))
    case Transition.ApplyFilter(plan) =>
      val retainedEvidence = plan.source.origin match
        case ValueOrigin.ToolOutput(producerStepId, _) => state.evidence.filterNot(_.producerStepId == producerStepId)
        case _                                         => state.evidence
      Right(state.copy(
        steps = state.steps ++ plan.addedSteps,
        available = state.available :+ plan.addedAvailable,
        evidence = retainedEvidence :+ plan.evidence,
        attemptedFilters = state.attemptedFilters + plan.fingerprint,
        nextOrdinal = plan.nextOrdinal,
      ))
    case Transition.AddSummary(step) =>
      Right(state.copy(steps = state.steps :+ step, summaryStep = Some(step.id), nextOrdinal = state.nextOrdinal + 1))
    case Transition.Finish(stepId) => Right(Workflow(state.steps, Expr.Ref(stepId, List("summary"))))

  private val ChoiceInstructions =
    "Choose the legal host-generated transition that best advances the user's prompt. " +
      "Treat candidate ids as opaque. Prefer gathering necessary evidence, choose summarize once evidence is sufficient, and finish after summarization."

  val semanticLoggingObserver: LoopObserver[PlanningError, Workflow] =
    LoopObserver.make:
      case LoopObservation.OptionsGenerated(iteration, _, options) =>
        ZIO.logInfo(s"Jev planning turn=$iteration options=${options.size} ids=[${options.map(_.id).mkString(",")}]")
      case LoopObservation.Decision(turn) =>
        val selectedProbability = turn.answer.probabilities.get(turn.choice).map(_.unwrap).fold("unavailable")(_.toString)
        ZIO.logInfo(
          s"Jev planning turn=${turn.iteration} selected=${turn.choice} probability=$selectedProbability " +
            s"confidence=${turn.answer.confidence.unwrap}"
        )
      case LoopObservation.Completion(result) =>
        ZIO.logInfo(
          s"Jev planning completed turns=${result.turns.size} inputTokens=${result.usage.inputTokens} " +
            s"outputTokens=${result.usage.outputTokens} latencyMs=${result.latencyMs}"
        )
      case LoopObservation.Failure(cause, turns) =>
        ZIO.logErrorCause(s"Jev planning failed after ${turns.size} completed decisions", cause)

  def planWithTypeSafeLoop(
    prompt: String,
    catalog: Vector[OperationSpec],
    config: OrchestrationConfig = OrchestrationConfig(),
    observer: LoopObserver[PlanningError, Workflow] = semanticLoggingObserver,
  ): ZIO[Client, Throwable, AuditedLoopResult[Workflow]] =
    val request = TypeSafeAI.loop[PlanState, Candidate, Any, PlanningError, Workflow](initial(prompt, catalog))(
      state => Content[Json](stateView(state)),
      state => candidates(state, config).flatMap(options =>
        ZIO.fromOption(NonEmptyChunk.fromIterableOption(options))
          .orElseFail(NoValidTransitions("No schema-safe operation or summary transition is available"))
          .map(_.map(candidate => LoopOption(
            candidate.id,
            candidate,
            Content[Json](candidate.view),
          )))
      ),
    ) { (state, selected) =>
      ZIO.fromEither(applyTransition(state, selected.transition)).map:
        case next: PlanState    => LoopStep.Continue(next)
        case workflow: Workflow => LoopStep.Done(workflow)
    }.choiceInstructions(ChoiceInstructions)
      .maxIterations(config.maxIterations)
      .observe(observer)

    val configured =
      if config.jevTurnRetries > 0 then request.retryEachTurn(LoopRetryPolicy(config.jevTurnRetries))
      else request

    configured.runAudited

  def planScripted(
    prompt: String,
    catalog: Vector[OperationSpec],
    choose: (PlanState, Vector[Candidate]) => Task[String],
    config: OrchestrationConfig = OrchestrationConfig(),
  ): Task[Workflow] =
    def loop(state: PlanState, iteration: Int): Task[Workflow] =
      if iteration >= config.maxIterations then ZIO.fail(IllegalStateException(s"Planning exceeded ${config.maxIterations} iterations"))
      else for
        options <- candidates(state, config)
        id <- choose(state, options)
        selected <- ZIO.fromOption(options.find(_.id == id)).orElseFail(IllegalArgumentException(s"Unknown candidate '$id'"))
        next <- ZIO.fromEither(applyTransition(state, selected.transition))
        result <- next match
          case nextState: PlanState => loop(nextState, iteration + 1)
          case workflow: Workflow   => ZIO.succeed(workflow)
      yield result
    loop(initial(prompt, catalog), 0)

  private[orchestration] def decisionOperationView(operation: OperationSpec): Json.Obj = Json.Obj(
    "name" -> Json.Str(operation.name),
    "kind" -> Json.Str(operation.kind.toString),
    "description" -> Json.Str(operation.description),
    "requiredInputs" -> Json.Arr(SchemaModel.required(operation.inputSchema).toVector.sorted.map(Json.Str(_))*),
    "outputs" -> Json.Arr(SchemaModel.indexOutput(operation.outputSchema).map(value => Json.Obj(
      "name" -> Json.Str(value.exposedName),
      "path" -> Json.Str(value.path.pointer),
      "isArray" -> Json.Bool(value.isArray),
    ))*),
  )

  private def candidateOperationView(operation: OperationSpec): Json.Obj = Json.Obj(
    "name" -> Json.Str(operation.name),
    "kind" -> Json.Str(operation.kind.toString),
  )

  def stateView(state: PlanState): Json.Obj = Json.Obj(
    "prompt" -> Json.Str(state.prompt),
    "operationCatalog" -> Json.Arr(state.catalog.map(decisionOperationView)*),
    "partialWorkflow" -> Json.Arr(state.steps.map(stepView)*),
    "evidenceCount" -> Json.Num(state.evidence.size),
  )

  private def directPlan(state: PlanState, operation: OperationSpec): Option[Candidate] =
    val props = SchemaModel.properties(operation.inputSchema)
    val bound = props.flatMap: (name, targetSchema) =>
      bestGlobal(state.available, name, targetSchema, operation.inputSchema, state.catalog, operation.name).map(value => (name, value.expr, originKey(value)))
    invocationCandidate(state, operation, InvocationMode.Direct, None, bound)

  private def filterCandidates(state: PlanState): Vector[Candidate] =
    state.available.filter(value => value.isArray && value.fanOutSafety == FanOutSafety.Unsafe)
      .sortBy(value => (originKey(value), value.path.pointer)).flatMap: source =>
        for
          sourceSchema <- source.schema.asObject.toVector
          itemSchema <- sourceSchema.get("items").flatMap(_.asObject).toVector
          if PredicateFilter.filterablePaths(itemSchema).nonEmpty
          fingerprint = s"filter:${originKey(source)}"
          if !state.attemptedFilters.contains(fingerprint)
        yield
          val synthId = f"s${state.nextOrdinal}%03d-filter-synthesis"
          val filterId = f"s${state.nextOrdinal + 1}%03d-filter"
          val synthesis = Step.Generate(synthId, Catalog.FilterName, Expr.Obj(Vector(
            "prompt" -> Expr.Literal(Json.Str(state.prompt)),
            "itemSchema" -> Expr.Literal(itemSchema),
          )))
          val filtering = Step.Filter(filterId, source.expr, itemSchema, state.prompt, Expr.Ref(synthId))
          val outputSchema = Json.Obj(
            "type" -> Json.Str("array"),
            "items" -> itemSchema,
          )
          val available = AvailableValue(
            Expr.Ref(filterId), outputSchema, source.exposedName, source.path,
            ValueOrigin.Filtered(filterId, originKey(source)), state.nextOrdinal + 1,
            isArray = true, fanOutSafety = FanOutSafety.ReadyFiltered,
          )
          val sourceOperation = source.origin match
            case ValueOrigin.InitialInput                       => "input"
            case ValueOrigin.ToolOutput(_, operation)           => operation
            case ValueOrigin.ExtractedArgument(_, operation)    => operation
            case ValueOrigin.Filtered(_, _)                      => Catalog.FilterName
          val evidence = EvidenceRef(
            filterId,
            sourceOperation,
            Expr.Obj(Vector(
              "operation" -> Expr.Literal(Json.Str(sourceOperation)),
              "mode" -> Expr.Literal(Json.Str("HostFiltered")),
              "output" -> Expr.Ref(filterId),
            )),
            filterId,
          )
          val plan = FilterPlan(source, itemSchema, Vector(synthesis, filtering), available, evidence, fingerprint, state.nextOrdinal + 2)
          Candidate("", Json.Obj(
            "transition" -> Json.Str("filter"),
            "source" -> Json.Obj(
              "origin" -> Json.Str(originKey(source)),
              "path" -> Json.Str(source.path.pointer),
              "filterablePaths" -> Json.Arr(PredicateFilter.filterablePaths(itemSchema).map(path =>
                Json.Str("/" + path.mkString("/"))
              )*),
            ),
            "capability" -> Json.Str(Catalog.FilterName),
          ), Transition.ApplyFilter(plan), s"filter:${originKey(source)}")

  private def fanOutPlans(state: PlanState, operation: OperationSpec, config: OrchestrationConfig): Vector[Candidate] =
    state.available.filter(value => value.isArray && (
      value.fanOutSafety == FanOutSafety.ReadyFiltered ||
        value.schema.asObject.flatMap(PredicateFilter.staticArrayBound).exists(_ <= config.hostGuard)
    )).sortBy(value => (originKey(value), value.path.fields.mkString("/"))).flatMap: source =>
      val itemSchema = source.schema.asObject.flatMap(_.get("items"))
      itemSchema.flatMap(_.asObject).toVector.flatMap: itemRoot =>
        val itemValues = SchemaModel.indexOutput(itemRoot).filterNot(_.isArray)
        val props = SchemaModel.properties(operation.inputSchema)
        val itemBindings = props.flatMap: (name, targetSchema) =>
          itemValues.filter(value =>
            value.exposedName == name &&
              SchemaModel.compatible(value.schema, targetSchema, itemRoot, operation.inputSchema) &&
              bindingMetadataCompatible(value.schema, targetSchema, originOperation(source, state.catalog), operation.name, state.catalog)
          )
            .sortBy(_.path.fields.mkString("/")).headOption
            .map(value => (name, Expr.Item(value.path.fields.toList), s"item:${source.path.pointer}:${value.path.pointer}"))
        if itemBindings.isEmpty then Vector.empty
        else
          val itemNames = itemBindings.map(_._1).toSet
          val global = props.filterNot((name, _) => itemNames.contains(name)).flatMap: (name, targetSchema) =>
            bestGlobal(state.available, name, targetSchema, operation.inputSchema, state.catalog, operation.name).map(value => (name, value.expr, originKey(value)))
          invocationCandidate(state, operation, InvocationMode.FanOut, Some(source), itemBindings ++ global).toVector

  private def invocationCandidate(
    state: PlanState,
    operation: OperationSpec,
    mode: InvocationMode,
    source: Option[AvailableValue],
    exactBindings: Vector[(String, Expr, String)],
  ): Option[Candidate] =
    val required = SchemaModel.required(operation.inputSchema).toVector.sorted
    val boundNames = exactBindings.map(_._1).toSet
    val missing = required.filterNot(boundNames)
    val fingerprint = InvocationFingerprint(
      operation.name, mode, source.map(originKey), exactBindings.map((name, _, origin) => name -> origin).sortBy(_._1), missing,
    )
    if state.attempted.contains(fingerprint) then None
    else
      val projected = Option.when(missing.nonEmpty)(SchemaModel.projectRequired(operation.inputSchema, missing))
      var ordinal = state.nextOrdinal
      val extractId = if missing.nonEmpty then
        val id = f"s$ordinal%03d-extract"
        ordinal += 1
        Some(id)
      else None
      val callId = f"s$ordinal%03d-call"
      ordinal += 1
      val evidenceId = f"s$ordinal%03d-evidence"
      ordinal += 1
      val globalKnown = exactBindings.filterNot(_._2.isInstanceOf[Expr.Item])
      val extractionStep = extractId.toVector.map: id =>
        Step.Generate(id, Catalog.ExtractName, Expr.Obj(Vector(
          "prompt" -> Expr.Literal(Json.Str(state.prompt)),
          "targetTool" -> Expr.Literal(Json.Str(operation.name)),
          "targetDescription" -> Expr.Literal(Json.Str(operation.description)),
          "targetInputSchema" -> Expr.Literal(operation.inputSchema),
          "outputSchema" -> Expr.Literal(projected.get),
          "knownArguments" -> Expr.Obj(globalKnown.map((name, expr, _) => name -> expr).sortBy(_._1)),
          "priorContext" -> Expr.Arr(state.evidence.map(_.expr)),
          "missingFields" -> Expr.Literal(Json.Arr(missing.map(Json.Str(_))*)),
        )))
      val extracted = missing.map(name => name -> Expr.Ref(extractId.get, List(name)))
      val arguments: Expr.Obj = Expr.Obj((exactBindings.map((name, expr, _) => name -> expr) ++ extracted).sortBy(_._1))
      val callStep: Step = mode match
        case InvocationMode.Direct => Step.Call(callId, operation.name, arguments)
        case InvocationMode.FanOut =>
          val authorization =
            if source.get.fanOutSafety == FanOutSafety.ReadyFiltered then FanOutAuthorization.ReadyFiltered
            else source.get.schema.asObject.flatMap(PredicateFilter.staticArrayBound)
              .map(FanOutAuthorization.StaticallyBound(_)).getOrElse(FanOutAuthorization.Unsafe)
          Step.FanOut(callId, source.get.expr, operation.name, arguments, authorization)
      val evidenceStep = Step.Construct(evidenceId, Expr.Obj(Vector(
        "operation" -> Expr.Literal(Json.Str(operation.name)),
        "mode" -> Expr.Literal(Json.Str(mode.toString)),
        "output" -> Expr.Ref(callId),
      )))
      val extractedAvailable = missing.zipWithIndex.map: (name, index) =>
        val schema = SchemaModel.properties(projected.get).toMap.getOrElse(name, Json.Obj())
        AvailableValue(Expr.Ref(extractId.get, List(name)), schema, name, SchemaPath(Vector(name)), ValueOrigin.ExtractedArgument(extractId.get, operation.name), state.nextOrdinal + index)
      val outputAvailable = mode match
        case InvocationMode.Direct => SchemaModel.indexOutput(operation.outputSchema).zipWithIndex.map: (value, index) =>
          AvailableValue(
            Expr.Ref(callId, value.path.fields.toList), value.schema, value.exposedName, value.path,
            ValueOrigin.ToolOutput(callId, operation.name), state.nextOrdinal + missing.size + index, value.isArray,
          )
        case InvocationMode.FanOut =>
          Vector(AvailableValue(
            Expr.Ref(callId),
            Json.Obj("type" -> Json.Str("array"), "items" -> operation.outputSchema),
            s"${operation.name}Results",
            SchemaPath(Vector.empty),
            ValueOrigin.ToolOutput(callId, operation.name),
            state.nextOrdinal + missing.size,
            isArray = true,
          ))
      val plan = InvocationPlan(
        operation, mode, source, exactBindings, missing, projected,
        extractionStep ++ Vector(callStep, evidenceStep), extractedAvailable ++ outputAvailable,
        EvidenceRef(evidenceId, operation.name, Expr.Ref(evidenceId), callId), fingerprint, ordinal,
      )
      val sourceView = source.fold[Json](Json.Null)(value => Json.Obj(
        "origin" -> Json.Str(originKey(value)), "path" -> Json.Str(value.path.pointer), "schema" -> value.schema,
      ))
      val view = Json.Obj(
        "transition" -> Json.Str("invoke"),
        "operation" -> operation.toJson,
        "mode" -> Json.Str(mode.toString),
        "source" -> sourceView,
        "exactBindings" -> Json.Obj(exactBindings.sortBy(_._1).map((name, expression, _) =>
          name -> Json.Str(if expression.isInstanceOf[Expr.Item] then "item" else "checkpoint")
        )*),
        "missingRequiredArguments" -> Json.Arr(missing.map(Json.Str(_))*),
        "hostWillInsertExtraction" -> Json.Bool(missing.nonEmpty),
      )
      Some(Candidate("", view, Transition.Invoke(plan), s"invoke:${operation.name}:${mode.toString}:${source.map(originKey).getOrElse("")}"))

  private def summaryCandidate(state: PlanState): Candidate =
    val id = f"s${state.nextOrdinal}%03d-summary"
    val step: Step.Generate = Step.Generate(id, Catalog.SummarizeName, Expr.Obj(Vector(
      "prompt" -> Expr.Literal(Json.Str(state.prompt)),
      "evidence" -> Expr.Arr(state.evidence.map(_.expr)),
    )))
    Candidate("", Json.Obj(
      "transition" -> Json.Str("summarize"),
      "description" -> Json.Str(Catalog.summarize.description),
      "evidenceCount" -> Json.Num(state.evidence.size),
    ), Transition.AddSummary(step), "zz-summary")

  private def finishCandidate(state: PlanState): Candidate =
    Candidate("", Json.Obj(
      "transition" -> Json.Str("finish"),
      "result" -> Json.Str(s"${state.summaryStep.get}.summary"),
    ), Transition.Finish(state.summaryStep.get), "finish")

  private def mentionedOperations(schema: Json, catalog: Vector[OperationSpec]): Set[String] =
    val description = schema.asObject.flatMap(_.get("description")).flatMap(_.asString)
      .getOrElse("").toLowerCase(java.util.Locale.ROOT)
    catalog.iterator.map(_.name).filter(name => description.contains(name.toLowerCase(java.util.Locale.ROOT))).toSet

  private def originOperation(value: AvailableValue, catalog: Vector[OperationSpec]): Option[String] = value.origin match
    case ValueOrigin.InitialInput                       => None
    case ValueOrigin.ToolOutput(_, operation)           => Some(operation)
    case ValueOrigin.ExtractedArgument(_, operation)    => Some(operation)
    case ValueOrigin.Filtered(_, parent) =>
      catalog.iterator.map(_.name).filter(name => parent.contains(s":$name:")).toVector.sortBy(-_.length).headOption

  private def bindingMetadataCompatible(
    sourceSchema: Json,
    targetSchema: Json,
    sourceOperation: Option[String],
    targetOperation: String,
    catalog: Vector[OperationSpec],
  ): Boolean =
    val declaredConsumers = mentionedOperations(sourceSchema, catalog)
    val declaredProducers = mentionedOperations(targetSchema, catalog)
    (declaredConsumers.isEmpty || declaredConsumers.contains(targetOperation)) &&
      (declaredProducers.isEmpty || sourceOperation.exists(declaredProducers.contains))

  private def bestGlobal(
    values: Vector[AvailableValue],
    name: String,
    target: Json,
    targetRoot: Json.Obj,
    catalog: Vector[OperationSpec],
    targetOperation: String,
  ): Option[AvailableValue] =
    values.filter(value => !value.isArray && value.exposedName == name && value.schema.asObject.exists(root =>
      SchemaModel.compatible(value.schema, target, root, targetRoot) &&
        bindingMetadataCompatible(value.schema, target, originOperation(value, catalog), targetOperation, catalog)
    )).sortBy(value => (-value.ordinal, value.path.fields.size, originKey(value), value.path.fields.mkString("/"))).headOption

  private def originKey(value: AvailableValue): String = value.origin match
    case ValueOrigin.InitialInput                         => s"input:${value.path.pointer}"
    case ValueOrigin.ToolOutput(step, operation)          => s"tool:$step:$operation:${value.path.pointer}"
    case ValueOrigin.ExtractedArgument(step, operation)   => s"extract:$step:$operation:${value.path.pointer}"
    case ValueOrigin.Filtered(step, parent)               => s"filter:$step:$parent:${value.path.pointer}"

  private def stepView(step: Step): Json = step match
    case Step.Call(id, operation, _) => Json.Obj(
      "id" -> Json.Str(id), "kind" -> Json.Str("call"), "operation" -> Json.Str(operation),
    )
    case Step.Construct(id, _) => Json.Obj("id" -> Json.Str(id), "kind" -> Json.Str("construct"))
    case Step.Generate(id, operation, _) => Json.Obj(
      "id" -> Json.Str(id), "kind" -> Json.Str("generate"), "operation" -> Json.Str(operation),
    )
    case Step.Filter(id, _, _, _, _) => Json.Obj("id" -> Json.Str(id), "kind" -> Json.Str("filter"))
    case Step.FanOut(id, _, operation, _, authorization) => Json.Obj(
      "id" -> Json.Str(id), "kind" -> Json.Str("fan_out"), "operation" -> Json.Str(operation),
      "authorization" -> Json.Str(authorization.toString),
    )
