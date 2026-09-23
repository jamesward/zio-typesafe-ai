package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.orchestration.model.*
import com.jamesward.zio_typesafe_ai.orchestration.runtime.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.codec.json.schemaJson

case class OrchestrationResult(
  mode: OrchestrationMode,
  workflow: Workflow,
  planningTurns: List[TypeSafeAI.LoopTurn],
  planningUsage: TypeSafeAI.Usage,
  planningLatencyMs: Long,
  execution: ExecutionReport,
  internalLlm: InternalLlmMetrics,
  finalText: String,
  metrics: OrchestrationMetrics,
  actionTrace: Vector[String] = Vector.empty,
  planningAudit: List[TypeSafeAI.LoopAuditTurn] = Nil,
)

case class OrchestrationEvidenceResult(
  workflow: Workflow,
  turns: List[TypeSafeAI.LoopTurn],
  usage: TypeSafeAI.Usage,
  latencyMs: Long,
  execution: ExecutionReport,
  evidence: Json.Arr,
  metrics: OrchestrationMetrics,
  actionTrace: Vector[String] = Vector.empty,
  audit: List[TypeSafeAI.LoopAuditTurn] = Nil,
)

enum EvidenceFilterMode:
  case Jev, Llm

case class JevFilterCapacityExceeded(variants: Int, threshold: Int) extends Throwable:
  override def getMessage: String =
    s"Jev-only filtering requires $variants variants, exceeding configured threshold $threshold"

object GenericOrchestrator:
  private case class PendingFilter(
    plan: FilterPlan,
    parentSource: Vector[Json],
    basis: Option[PredicateFilter.Evaluation],
    synthesisRejection: Option[PredicateFilter.SynthesisRejection],
    seenPredicates: Set[String],
    attemptsUsed: Int,
  )


  private case class ActionFailure(operation: String, mode: String, message: String)
  private case class NoPlanState(
    plan: PlanState,
    values: Map[String, Json],
    executionMetrics: model.ExecutionMetrics,
    trace: Vector[String],
    pendingFilter: Option[PendingFilter] = None,
    lastActionFailure: Option[ActionFailure] = None,
  )
  private case class NoPlanDone(state: NoPlanState, workflow: Workflow)

  private case class NoPlanBehavior(
    input: Json,
    initialPlan: PlanState,
    evidenceOnly: Boolean,
    exactBindingsOnly: Boolean,
    allowLlmFilterFallback: Boolean,
  )

  private enum NoPlanAction:
    case Planned(candidate: Candidate)
    case Recover
    case FinishEvidence

  private case class NoPlanOption(id: String, view: Json.Obj, action: NoPlanAction)

  private enum FilterAttemptResult:
    case Accepted(predicate: Json.Obj, evaluation: PredicateFilter.Evaluation)
    case Rejected(rejection: PredicateFilter.SynthesisRejection)

  private case class FilterAttempt(
    result: FilterAttemptResult,
    metrics: model.ExecutionMetrics,
    engine: String,
  )

  def run(
    prompt: String,
    catalog: Vector[OperationSpec],
    operationInvoker: OperationInvoker,
    internalLlm: InternalLlmInvoker,
    config: OrchestrationConfig = OrchestrationConfig(),
    planningObserver: TypeSafeAI.LoopObserver[GenericPlanner.PlanningError, Workflow] = GenericPlanner.semanticLoggingObserver,
  ): ZIO[TypeSafeAI.Client, Throwable, OrchestrationResult] =
    runMode(prompt, catalog, operationInvoker, internalLlm, OrchestrationMode.Plan, config, ZIO.succeed(JevPhysicalMetrics()), planningObserver)

  def runMode(
    prompt: String,
    catalog: Vector[OperationSpec],
    operationInvoker: OperationInvoker,
    internalLlm: InternalLlmInvoker,
    mode: OrchestrationMode,
    config: OrchestrationConfig = OrchestrationConfig(),
    physicalMetrics: UIO[JevPhysicalMetrics] = ZIO.succeed(JevPhysicalMetrics()),
    planningObserver: TypeSafeAI.LoopObserver[GenericPlanner.PlanningError, Workflow] = GenericPlanner.semanticLoggingObserver,
  ): ZIO[TypeSafeAI.Client, Throwable, OrchestrationResult] =
    for
      started <- Clock.nanoTime
      filterJevTracker <- JevFilterTracker.make
      client <- ZIO.service[Client]
      core <- if mode.usesPlanExecution then
        runPlan(prompt, catalog, operationInvoker, internalLlm, config, planningObserver)
      else runNoPlan(
        prompt,
        catalog,
        operationInvoker,
        internalLlm,
        config,
        client,
        filterJevTracker,
        NoPlanBehavior(
          Json.Str(prompt),
          GenericPlanner.initial(prompt, catalog),
          evidenceOnly = false,
          exactBindingsOnly = false,
          allowLlmFilterFallback = true,
        ),
      )
      (workflow, turns, usage, latency, report, audit, trace) = core
      text <- ZIO.fromOption(report.output.asString)
        .orElseFail(IllegalStateException(s"Summary workflow returned non-string output: ${report.output}"))
      internal <- internalLlm.metrics
      mcp <- operationInvoker match
        case provider: McpPhysicalMetricsProvider => provider.mcpPhysicalMetrics
        case _                                    => ZIO.succeed(McpPhysicalMetrics())
      physical <- physicalMetrics
      filterJev <- filterJevTracker.metrics
      finished <- Clock.nanoTime
      metrics = commonMetrics(mode, turns, usage, latency, physical, filterJev, internal, mcp, report.metrics, (finished - started) / 1000000L)
    yield OrchestrationResult(mode, workflow, turns, usage, latency, report, internal, text, metrics, trace, audit)

  /** Run a runtime-checkpoint Jev loop as an evidence-producing capability.
    * Only exact host bindings and external operations are available. Extraction
    * and summarization cannot run; predicate generation is opt-in via filterMode. */
  def runEvidence(
    prompt: String,
    catalog: Vector[OperationSpec],
    initialInput: Json.Obj,
    initialInputSchema: Json.Obj,
    operationInvoker: OperationInvoker,
    config: OrchestrationConfig = OrchestrationConfig(),
    physicalMetrics: UIO[JevPhysicalMetrics] = ZIO.succeed(JevPhysicalMetrics()),
    filterMode: EvidenceFilterMode = EvidenceFilterMode.Jev,
    filterLlm: Option[InternalLlmInvoker] = None,
  ): ZIO[TypeSafeAI.Client, Throwable, OrchestrationEvidenceResult] =
    for
      _ <- ZIO.fromEither(SchemaModel.validateObject(initialInput, initialInputSchema)).mapError(errors =>
        IllegalArgumentException(s"Initial orchestration input failed schema validation: ${errors.mkString("; ")}")
      )
      _ <- ZIO.fail(IllegalArgumentException("jevFilterVariantThreshold must be non-negative"))
        .unless(config.jevFilterVariantThreshold >= 0)
      started <- Clock.nanoTime
      filterJevTracker <- JevFilterTracker.make
      client <- ZIO.service[Client]
      internalLlm <- filterMode match
        case EvidenceFilterMode.Jev => InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) =
            ZIO.fail(UnsupportedOperationException("Evidence-only orchestration does not support LLM extraction"))
          def summarize(prompt: String, evidence: Json.Arr) =
            ZIO.fail(UnsupportedOperationException("Evidence-only orchestration does not support LLM summarization"))
        )
        case EvidenceFilterMode.Llm =>
          ZIO.fromOption(filterLlm).orElseFail(IllegalArgumentException(
            "EvidenceFilterMode.Llm requires a filter-capable InternalLlmInvoker"
          ))
      effectiveConfig = filterMode match
        case EvidenceFilterMode.Jev => config
        case EvidenceFilterMode.Llm => config.copy(jevFilterVariantThreshold = 0)
      allowLlmFilterFallback = filterMode match
        case EvidenceFilterMode.Jev => false
        case EvidenceFilterMode.Llm => true
      mcpCatalog = catalog.filter(_.kind == CapabilityKind.Mcp)
      core <- runNoPlan(
        prompt,
        mcpCatalog,
        operationInvoker,
        internalLlm,
        effectiveConfig,
        client,
        filterJevTracker,
        NoPlanBehavior(
          initialInput,
          GenericPlanner.initial(prompt, mcpCatalog, initialInput, initialInputSchema),
          evidenceOnly = true,
          exactBindingsOnly = true,
          allowLlmFilterFallback = allowLlmFilterFallback,
        ),
      )
      (workflow, turns, usage, latency, report, audit, trace) = core
      evidence <- ZIO.fromOption(report.output.asArray.map(values => Json.Arr(values)))
        .orElseFail(IllegalStateException(s"Evidence workflow returned non-array output: ${report.output}"))
      internal <- internalLlm.metrics
      mcp <- operationInvoker match
        case provider: McpPhysicalMetricsProvider => provider.mcpPhysicalMetrics
        case _                                    => ZIO.succeed(McpPhysicalMetrics())
      physical <- physicalMetrics
      filterJev <- filterJevTracker.metrics
      finished <- Clock.nanoTime
      metrics = commonMetrics(
        OrchestrationMode.NoPlan,
        turns,
        usage,
        latency,
        physical,
        filterJev,
        internal,
        mcp,
        report.metrics,
        (finished - started) / 1000000L,
      )
    yield OrchestrationEvidenceResult(workflow, turns, usage, latency, report, evidence, metrics, trace, audit)

  private type CoreResult = (
    Workflow,
    List[LoopTurn],
    Usage,
    Long,
    ExecutionReport,
    List[LoopAuditTurn],
    Vector[String],
  )

  private def runPlan(
    prompt: String,
    catalog: Vector[OperationSpec],
    operations: OperationInvoker,
    internalLlm: InternalLlmInvoker,
    config: OrchestrationConfig,
    observer: LoopObserver[GenericPlanner.PlanningError, Workflow],
  ): ZIO[Client, Throwable, CoreResult] =
    for
      planning <- GenericPlanner.planWithTypeSafeLoop(prompt, catalog, config, observer)
      executed <- execute(prompt, planning.output, operations, internalLlm, config)
      (report, _, _) = executed
      recoveryTrace = Vector.tabulate(report.metrics.recoveries)(index => s"checkpoint-continuation:${index + 1}")
    yield (planning.output, planning.turns, planning.usage, planning.latencyMs, report, planning.audit, recoveryTrace)

  private def runNoPlan(
    prompt: String,
    catalog: Vector[OperationSpec],
    operations: OperationInvoker,
    internalLlm: InternalLlmInvoker,
    config: OrchestrationConfig,
    client: Client,
    filterJevTracker: JevFilterTracker,
    behavior: NoPlanBehavior,
  ): ZIO[Client, Throwable, CoreResult] =
    val initial = NoPlanState(behavior.initialPlan, Map.empty, ExecutionMetricOps.empty, Vector.empty)
    val request = TypeSafeAI.loop[NoPlanState, NoPlanOption, Any, Throwable, NoPlanDone](initial)(
      state => Content[Json](noPlanStateView(state)),
      state => noPlanOptions(state, config, behavior).flatMap(options =>
        ZIO.fromOption(NonEmptyChunk.fromIterableOption(options))
          .orElseFail(noPlanExhausted(state, config))
          .map(_.map(option => LoopOption(option.id, option, Content[Json](option.view))))
      ),
    ) { (state, selected) =>
      selected.action match
        case NoPlanAction.FinishEvidence =>
          val evidence = usableEvidence(state)
          val workflow = Workflow(state.plan.steps, Expr.Arr(evidence.map(_.expr)))
          ZIO.succeed(LoopStep.Done(NoPlanDone(state.copy(trace = state.trace :+ "return-evidence"), workflow)))
        case NoPlanAction.Recover =>
          recoverNoPlanFilter(
            state,
            internalLlm,
            config,
            client,
            filterJevTracker,
            behavior.allowLlmFilterFallback,
          ).map(LoopStep.Continue(_))
        case NoPlanAction.Planned(candidate) => candidate.transition match
          case Transition.Finish(stepId) =>
            val workflow = GenericPlanner.applyTransition(state.plan, candidate.transition).toOption.get.asInstanceOf[Workflow]
            ZIO.succeed(LoopStep.Done(NoPlanDone(state.copy(trace = state.trace :+ s"finish:$stepId"), workflow)))
          case Transition.ApplyFilter(plan) =>
            startNoPlanFilter(
              state,
              plan,
              internalLlm,
              config,
              client,
              filterJevTracker,
              behavior.input,
              behavior.allowLlmFilterFallback,
            ).map(LoopStep.Continue(_))
          case transition =>
            val effectiveTransition = transition match
              case Transition.AddSummary(step) => Transition.AddSummary(summaryStepForCheckpoint(step, state))
              case other                       => other
            val steps = effectiveTransition match
              case Transition.Invoke(plan)     => plan.addedSteps
              case Transition.AddSummary(step) => Vector(step)
              case _                           => Vector.empty
            val actionName = effectiveTransition match
              case Transition.Invoke(plan)  => s"invoke:${plan.operation.name}:${plan.mode.toString.toLowerCase}"
              case Transition.AddSummary(_) => "summarize"
              case _                        => "finish"
            for
              nextPlan <- ZIO.fromEither(GenericPlanner.applyTransition(state.plan, effectiveTransition)).map(_.asInstanceOf[PlanState])
              remainingRecoveries = math.max(0, config.maxRecoveries - state.executionMetrics.recoveries)
              segmentExit <- WorkflowRuntime.execute(
                Workflow(steps, Expr.Ref(steps.last.id)),
                behavior.input,
                operations,
                internalLlm,
                executionPolicy(config).copy(maxRecoveries = remainingRecoveries),
                state.values,
              ).exit
              next <- segmentExit match
                case Exit.Success(segment) =>
                  val newlyAvailable = effectiveTransition match
                    case Transition.Invoke(plan) => plan.addedAvailable
                    case _                       => Vector.empty
                  for
                    promotedPlan <- promoteSingletonArrays(nextPlan, segment.values, newlyAvailable, behavior.input)
                    continuationTrace = Vector.tabulate(segment.metrics.recoveries): index =>
                      s"checkpoint-continuation:${state.executionMetrics.recoveries + index + 1}"
                  yield NoPlanState(
                    promotedPlan,
                    segment.values,
                    ExecutionMetricOps.combine(state.executionMetrics, segment.metrics),
                    (state.trace :+ actionName) ++ continuationTrace,
                    state.pendingFilter,
                    None,
                  )
                case Exit.Failure(cause) => effectiveTransition match
                  case Transition.Invoke(plan) =>
                    val rawMessage = cause.failureOption.orElse(cause.dieOption)
                      .flatMap(error => Option(error.getMessage)).getOrElse("operation failed")
                      .replaceAll("\\s+", " ")
                    val message = if rawMessage.length <= 240 then rawMessage else rawMessage.substring(0, 239) + "…"
                    val failedPlan = state.plan.copy(
                      attempted = state.plan.attempted + plan.fingerprint,
                      operationCounts = state.plan.operationCounts.updated(
                        plan.operation.name,
                        state.plan.operationCounts.getOrElse(plan.operation.name, 0) + 1,
                      ),
                      nextOrdinal = math.max(state.plan.nextOrdinal, plan.nextOrdinal),
                    )
                    ZIO.succeed(state.copy(
                      plan = failedPlan,
                      trace = state.trace :+ actionName :+ s"invoke-failed:${plan.operation.name}:${plan.mode.toString.toLowerCase}",
                      lastActionFailure = Some(ActionFailure(plan.operation.name, plan.mode.toString, message)),
                    ))
                  case _ => ZIO.failCause(cause)
            yield LoopStep.Continue(next)
    }.choiceInstructions(
      if behavior.evidenceOnly then
        "Choose the legal host-generated runtime action that best advances the request. Actions execute before the next decision; return evidence once it is sufficient for the caller."
      else
        "Choose the legal host-generated runtime action that best advances the user request. Actions execute before the next decision; summarize when evidence is sufficient, then finish."
    ).maxIterations(config.maxIterations)

    val configured = if config.jevTurnRetries > 0 then request.retryEachTurn(LoopRetryPolicy(config.jevTurnRetries)) else request
    for
      result <- configured.runAudited
      done = result.output
      report <-
        if behavior.evidenceOnly then
          ZIO.foreach(usableEvidence(done.state))(evidence =>
            resolveHostValue(evidence.expr, done.state.values, behavior.input)
          ).map(values => ExecutionReport(Json.Arr(values*), done.state.values, done.state.executionMetrics))
        else
          for
            summaryId <- ZIO.fromOption(done.workflow.result match
              case Expr.Ref(id, List("summary")) => Some(id)
              case _                              => None
            ).orElseFail(IllegalStateException("No-plan workflow did not finish with summary"))
            summary <- ZIO.fromOption(done.state.values.get(summaryId).flatMap(_.asObject).flatMap(_.get("summary")).flatMap(_.asString))
              .orElseFail(IllegalStateException("No-plan summary value is missing"))
          yield ExecutionReport(Json.Str(summary), done.state.values, done.state.executionMetrics)
    yield (done.workflow, result.turns, result.usage, result.latencyMs, report, result.audit, done.state.trace)

  private def usableEvidence(state: NoPlanState): Vector[EvidenceRef] =
    val excludedProducer = state.pendingFilter.flatMap(_.plan.source.origin match
      case ValueOrigin.ToolOutput(stepId, _) => Some(stepId)
      case _                                 => None
    )
    excludedProducer.fold(state.plan.evidence)(producer =>
      state.plan.evidence.filterNot(_.producerStepId == producer)
    )

  private def summaryStepForCheckpoint(step: Step.Generate, state: NoPlanState): Step.Generate =
    val evidence = usableEvidence(state)
    step.copy(arguments = Expr.Obj(Vector(
      "prompt" -> Expr.Literal(Json.Str(state.plan.prompt)),
      "evidence" -> Expr.Arr(evidence.map(_.expr)),
    )))

  private def noPlanOptions(
    state: NoPlanState,
    config: OrchestrationConfig,
    behavior: NoPlanBehavior,
  ): IO[Throwable, Vector[NoPlanOption]] =
    val planned = GenericPlanner.candidates(state.plan, config)
      .catchSome { case _: GenericPlanner.NoValidTransitions => ZIO.succeed(Vector.empty) }
      .map(_.filterNot(candidate => state.pendingFilter.exists(pending => candidate.transition match
        case Transition.ApplyFilter(plan) => plan.fingerprint == pending.plan.fingerprint
        case _                            => false
      )))
    planned.map: candidates =>
      val recovery = state.pendingFilter.filter(_ => state.plan.summaryStep.isEmpty).filter(pending =>
        pending.attemptsUsed < config.maxFilterAttempts && state.executionMetrics.recoveries < config.maxRecoveries
      ).map(_ => NoPlanOption("", recoveryCandidateView(state.pendingFilter.get), NoPlanAction.Recover)).toVector
      val ordinary = candidates.flatMap: candidate =>
        candidate.transition match
          case Transition.Invoke(plan) if behavior.exactBindingsOnly && plan.missing.nonEmpty => Vector.empty
          case Transition.AddSummary(_) if behavior.evidenceOnly => Vector(NoPlanOption(
            "",
            Json.Obj(
              "transition" -> Json.Str("return_evidence"),
              "evidenceCount" -> Json.Num(usableEvidence(state).size),
            ),
            NoPlanAction.FinishEvidence,
          ))
          case Transition.Finish(_) if behavior.evidenceOnly => Vector.empty
          case _ => Vector(NoPlanOption("", candidate.view, NoPlanAction.Planned(candidate)))
      (recovery ++ ordinary).zipWithIndex.map: (option, index) =>
        val id = f"c$index%03d"
        val view = Json.Obj(option.view.fields.filterNot(_._1 == "candidateId") :+ ("candidateId" -> Json.Str(id)))
        option.copy(id = id, view = view)

  private def noPlanExhausted(state: NoPlanState, config: OrchestrationConfig): Throwable =
    state.pendingFilter match
      case Some(pending) =>
        val status = pending.basis.map(_.classification.toString)
          .orElse(pending.synthesisRejection.map(_.kind.wireName)).getOrElse("filter synthesis")
        val attempts = s"filter attempts ${pending.attemptsUsed}/${config.maxFilterAttempts}"
        val recoveries = s"recoveries ${state.executionMetrics.recoveries}/${config.maxRecoveries}"
        GenericPlanner.NoValidTransitions(s"No legal no-plan action remains after $status; $attempts, $recoveries")
      case None => GenericPlanner.NoValidTransitions("No schema-safe runtime action is available")

  private def startNoPlanFilter(
    state: NoPlanState,
    plan: FilterPlan,
    internalLlm: InternalLlmInvoker,
    config: OrchestrationConfig,
    client: Client,
    filterJevTracker: JevFilterTracker,
    runtimeInput: Json,
    allowLlmFilterFallback: Boolean,
  ): Task[NoPlanState] =
    for
      _ <- ZIO.fail(IllegalArgumentException("jevFilterVariantThreshold must be non-negative")).unless(config.jevFilterVariantThreshold >= 0)
      sourceValue <- resolveHostValue(plan.source.expr, state.values, runtimeInput)
      source <- ZIO.fromOption(sourceValue.asArray.map(_.toVector)).orElseFail(model.ExecutionError.ExpectedArray(sourceValue))
      next <-
        if source.isEmpty then
          ZIO.succeed(state.copy(
            plan = state.plan.copy(
              attemptedFilters = state.plan.attemptedFilters + plan.fingerprint,
              nextOrdinal = math.max(state.plan.nextOrdinal, plan.nextOrdinal),
            ),
            trace = state.trace :+ s"filter:${plan.fingerprint}" :+ "filter-skipped:empty" :+
              "filter-outcome:NoMatches:0/0:attempt=0",
            pendingFilter = None,
            lastActionFailure = None,
          ))
        else
          for
            attempt <- synthesizeAndEvaluate(
              plan.itemSchema,
              source,
              PredicateFilter.SynthesisRequest(state.plan.prompt, plan.itemSchema, None, None),
              config.hostGuard,
              None,
              Set.empty,
              recovery = false,
              config,
              client,
              filterJevTracker,
              allowLlmFilterFallback,
            )(internalLlm)
            finished <- finishFilterAttempt(state, plan, attempt, source, attemptNumber = 1)
          yield finished
    yield next

  private def recoverNoPlanFilter(
    state: NoPlanState,
    internalLlm: InternalLlmInvoker,
    config: OrchestrationConfig,
    client: Client,
    filterJevTracker: JevFilterTracker,
    allowLlmFilterFallback: Boolean,
  ): Task[NoPlanState] =
    for
      pending <- ZIO.fromOption(state.pendingFilter).orElseFail(IllegalStateException("Selected filter recovery without a pending filter outcome"))
      _ <- ZIO.fail(noPlanExhausted(state, config)).unless(
        pending.attemptsUsed < config.maxFilterAttempts && state.executionMetrics.recoveries < config.maxRecoveries
      )
      source = pending.basis match
        case Some(evaluation) if evaluation.classification == PredicateFilter.Classification.TooBroad => evaluation.matches
        case _ => pending.parentSource
      priorCount = pending.basis.collect:
        case evaluation if evaluation.classification == PredicateFilter.Classification.TooBroad => evaluation.matches.size
      request = PredicateFilter.SynthesisRequest(
        state.plan.prompt,
        pending.plan.itemSchema,
        pending.basis.map(_.canonicalPredicate),
        pending.basis.map(filterDiagnostic),
        pending.synthesisRejection,
      )
      attempt <- synthesizeAndEvaluate(
        pending.plan.itemSchema,
        source,
        request,
        config.hostGuard,
        priorCount,
        pending.seenPredicates,
        recovery = true,
        config,
        client,
        filterJevTracker,
        allowLlmFilterFallback,
      )(internalLlm)
      next <- finishFilterAttempt(state, pending.plan, attempt, source, pending.attemptsUsed + 1)
    yield next

  private def synthesizeAndEvaluate(
    itemSchema: Json.Obj,
    source: Vector[Json],
    request: PredicateFilter.SynthesisRequest,
    hostGuard: Int,
    priorMatchCount: Option[Int],
    seenPredicates: Set[String],
    recovery: Boolean,
    config: OrchestrationConfig,
    client: Client,
    filterJevTracker: JevFilterTracker,
    allowLlmFilterFallback: Boolean,
  )(internalLlm: InternalLlmInvoker): Task[FilterAttempt] =
    def evaluateAccepted(predicate: PredicateFilter.Predicate): Task[FilterAttemptResult] =
      val canonical = PredicateFilter.canonical(predicate)
      if seenPredicates.contains(canonical.toJson) then
        ZIO.succeed(FilterAttemptResult.Rejected(PredicateFilter.SynthesisRejection(
          PredicateFilter.SynthesisRejectionKind.RepeatedPredicate,
          "Predicate duplicated a previously accepted canonical predicate",
        )))
      else
        ZIO.fromEither(PredicateFilter.classify(source, predicate, hostGuard, priorMatchCount))
          .map(FilterAttemptResult.Accepted(canonical, _))

    for
      started <- Clock.nanoTime
      prepared = JevVariantFilter.prepare(source, itemSchema)
      routed <- JevVariantFilter.route(prepared.variants.size, config.jevFilterVariantThreshold) match
        case JevVariantFilter.Route.Jev =>
          for
            selection <- JevVariantFilter.select(
              request.prompt, prepared, client, filterJevTracker, config.maxParallelism, request,
            )
            _ <- ZIO.logInfo(
              s"Filter engine=jev variants=${prepared.variants.size} batches=${selection.batchCount} sourceCount=${source.size}"
            )
            evaluated <- evaluateAccepted(selection.predicate)
          yield evaluated -> s"jev:${selection.batchCount}"
        case JevVariantFilter.Route.Llm if !allowLlmFilterFallback =>
          ZIO.fail(JevFilterCapacityExceeded(prepared.variants.size, config.jevFilterVariantThreshold))
        case JevVariantFilter.Route.Llm =>
          val evidence = JevVariantFilter.llmEvidence(request.prompt, source.size, prepared)
          for
            _ <- ZIO.logInfo(
              s"Filter engine=llm variants=${prepared.variants.size} evidenceVariants=${evidence.variants.elements.size} sourceCount=${source.size}"
            )
            generated <- internalLlm.generate(Catalog.FilterName, request.copy(evidence = Some(evidence)).toJson)
            envelope <- ZIO.fromEither(PredicateFilter.SynthesisResult.fromGenerativeValue(generated))
            evaluated <- envelope match
              case PredicateFilter.SynthesisResult.Rejected(rejection) =>
                ZIO.succeed(FilterAttemptResult.Rejected(rejection))
              case PredicateFilter.SynthesisResult.Accepted(criteria) =>
                ZIO.fromEither(PredicateFilter.parseAndValidate(criteria, itemSchema)).flatMap(evaluateAccepted)
          yield evaluated -> s"llm:${evidence.variants.elements.size}"
      finished <- Clock.nanoTime
      elapsed = (finished - started) / 1000000L
      metrics = model.ExecutionMetrics(0, 1, 0L, 0L, elapsed, elapsed, recoveries = if recovery then 1 else 0)
    yield FilterAttempt(routed._1, metrics, routed._2)

  private def finishFilterAttempt(
    state: NoPlanState,
    plan: FilterPlan,
    attempt: FilterAttempt,
    attemptedSource: Vector[Json],
    attemptNumber: Int,
  ): Task[NoPlanState] =
    val actionTrace =
      if attemptNumber == 1 then Vector(s"filter:${plan.fingerprint}")
      else Vector(s"filter-recovery:${recoveryKind(state.pendingFilter.flatMap(_.basis))}:${plan.fingerprint}:attempt=$attemptNumber")
    val engineTrace = s"filter-engine:${attempt.engine}:attempt=$attemptNumber"
    val resultTrace = attempt.result match
      case FilterAttemptResult.Accepted(_, evaluation) =>
        s"filter-outcome:${evaluation.classification}:${evaluation.matches.size}/${evaluation.source.size}:attempt=$attemptNumber"
      case FilterAttemptResult.Rejected(rejection) =>
        s"filter-rejection:${rejection.kind.wireName}:attempt=$attemptNumber"
    val metrics = ExecutionMetricOps.combine(state.executionMetrics, attempt.metrics)
    attempt.result match
      case FilterAttemptResult.Accepted(predicate, evaluation)
          if evaluation.classification == PredicateFilter.Classification.Ready =>
        for
          ids <- filterStepIds(plan)
          (synthesisId, filterId) = ids
          recordedSteps =
            if attempt.engine.startsWith("jev:") then plan.addedSteps.map:
              case Step.Generate(id, Catalog.FilterName, _) => Step.Construct(id, Expr.Literal(predicate))
              case step                                     => step
            else plan.addedSteps
          transitionPlan = plan.copy(
            addedSteps = recordedSteps,
            nextOrdinal = math.max(plan.nextOrdinal, state.plan.nextOrdinal),
          )
          committed <- ZIO.fromEither(GenericPlanner.applyTransition(state.plan, Transition.ApplyFilter(transitionPlan)))
            .map(_.asInstanceOf[PlanState])
          nextValues = state.values.updated(synthesisId, predicate).updated(filterId, Json.Arr(evaluation.matches*))
          promoted <- promoteSingletonArrays(committed, nextValues, Vector(plan.addedAvailable))
        yield state.copy(
          plan = promoted,
          values = nextValues,
          executionMetrics = metrics,
          trace = state.trace ++ actionTrace :+ engineTrace :+ resultTrace,
          pendingFilter = None,
          lastActionFailure = None,
        )
      case result =>
        val previous = state.pendingFilter.filter(_.plan.fingerprint == plan.fingerprint)
        val nextBasis = result match
          case FilterAttemptResult.Accepted(_, evaluation) => Some(evaluation)
          case FilterAttemptResult.Rejected(_)             => previous.flatMap(_.basis)
        val nextRejection = result match
          case FilterAttemptResult.Accepted(_, _)      => None
          case FilterAttemptResult.Rejected(rejection) => Some(rejection)
        val nextSeen = result match
          case FilterAttemptResult.Accepted(predicate, _) => previous.map(_.seenPredicates).getOrElse(Set.empty) + predicate.toJson
          case FilterAttemptResult.Rejected(_)             => previous.map(_.seenPredicates).getOrElse(Set.empty)
        val retainedParent = result match
          case FilterAttemptResult.Accepted(_, _) => attemptedSource
          case FilterAttemptResult.Rejected(_)    => previous.map(_.parentSource).getOrElse(attemptedSource)
        val pending = PendingFilter(plan, retainedParent, nextBasis, nextRejection, nextSeen, attemptNumber)
        ZIO.succeed(state.copy(
          plan = state.plan.copy(nextOrdinal = math.max(state.plan.nextOrdinal, plan.nextOrdinal)),
          executionMetrics = metrics,
          trace = state.trace ++ actionTrace :+ engineTrace :+ resultTrace,
          pendingFilter = Some(pending),
          lastActionFailure = None,
        ))

  private def filterStepIds(plan: FilterPlan): Task[(String, String)] = plan.addedSteps match
    case Vector(Step.Generate(synthesisId, Catalog.FilterName, _), Step.Filter(filterId, _, _, _, _)) =>
      ZIO.succeed(synthesisId -> filterId)
    case _ => ZIO.fail(IllegalStateException(s"Invalid symbolic filter plan for ${plan.fingerprint}"))

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

  private def predicateView(canonical: Json.Obj): Json.Obj =
    canonical.get("_hostType").flatMap(_.asString) match
      case Some("filter_variant_membership_v1") => Json.Obj(
        "kind" -> Json.Str("variant_membership"),
        "paths" -> canonical.get("paths").getOrElse(Json.Arr()),
        "selectedCount" -> Json.Num(canonical.get("fingerprints").flatMap(_.asArray).fold(0)(_.size)),
      )
      case _ => canonical

  private def recoveryKind(basis: Option[PredicateFilter.Evaluation]): String = basis match
    case Some(evaluation) if evaluation.classification == PredicateFilter.Classification.TooBroad => "refine"
    case Some(_) => "replace"
    case None    => "retry"

  private def evaluationView(evaluation: PredicateFilter.Evaluation): Json.Obj =
    Json.Obj(
      "classification" -> Json.Str(evaluation.classification.toString),
      "sourceCount" -> Json.Num(evaluation.source.size),
      "matchCount" -> Json.Num(evaluation.matches.size),
      "predicate" -> predicateView(evaluation.canonicalPredicate),
    )

  private def rejectionView(rejection: PredicateFilter.SynthesisRejection): Json.Obj =
    Json.Obj(
      "kind" -> Json.Str(rejection.kind.wireName),
      "message" -> Json.Str(rejection.message),
    )

  private def recoveryCandidateView(pending: PendingFilter): Json.Obj =
    val base = Vector(
      "transition" -> Json.Str("filter_recovery"),
      "capability" -> Json.Str(Catalog.FilterName),
      "action" -> Json.Str(recoveryKind(pending.basis)),
      "sourceCount" -> Json.Num(pending.parentSource.size),
      "nextAttempt" -> Json.Num(pending.attemptsUsed + 1),
    )
    val evaluation = pending.basis.toVector.flatMap: basis =>
      val diagnostic = filterDiagnostic(basis)
      Vector(
        "priorPredicate" -> predicateView(basis.canonicalPredicate),
        "outcomeDiagnostics" -> Json.Obj(
          "classification" -> Json.Str(diagnostic.classification),
          "sourceCount" -> Json.Num(diagnostic.sourceCount),
          "matchCount" -> Json.Num(diagnostic.matchCount),
          "message" -> Json.Str(diagnostic.message),
        ),
      )
    val rejection = pending.synthesisRejection.toVector.map(value => "synthesisRejection" -> rejectionView(value))
    Json.Obj(Chunk.fromIterable(base ++ evaluation ++ rejection))

  private def resolveHostValue(expr: Expr, values: Map[String, Json], input: Json = Json.Null): Task[Json] =
    def descend(value: Json, path: List[String], error: => Throwable): Task[Json] =
      path.foldLeft[Task[Json]](ZIO.succeed(value)): (current, field) =>
        current.flatMap(json => ZIO.fromOption(json.asObject.flatMap(_.get(field))).orElseFail(error))

    expr match
      case Expr.Input(path) =>
        descend(input, path, model.ExecutionError.MissingInput(path))
      case Expr.Ref(stepId, path) =>
        ZIO.fromOption(values.get(stepId)).orElseFail(model.ExecutionError.MissingReference(stepId, path)).flatMap: value =>
          descend(value, path, model.ExecutionError.MissingReference(stepId, path))
      case Expr.At(source, index, path) =>
        for
          _ <- ZIO.fail(model.ExecutionError.InvalidIndex(index)).when(index < 0)
          sourceValue <- resolveHostValue(source, values, input)
          items <- ZIO.fromOption(sourceValue.asArray).orElseFail(model.ExecutionError.ExpectedArray(sourceValue))
          selected <- ZIO.fromOption(items.lift(index)).orElseFail(model.ExecutionError.IndexOutOfBounds(index, items.size))
          projected <- descend(selected, path, model.ExecutionError.MissingAtPath(index, path))
        yield projected
      case Expr.Literal(value) => ZIO.succeed(value)
      case Expr.Obj(fields) =>
        ZIO.foreach(fields): (name, value) =>
          resolveHostValue(value, values, input).map(name -> _)
        .map(resolved => Json.Obj(resolved*))
      case Expr.Arr(items) =>
        ZIO.foreach(items)(resolveHostValue(_, values, input)).map(resolved => Json.Arr(resolved*))
      case other => ZIO.fail(IllegalStateException(s"Unsupported no-plan filter source expression: $other"))

  private[orchestration] def promoteSingletonArrays(
    plan: PlanState,
    values: Map[String, Json],
    newlyAvailable: Vector[AvailableValue],
    input: Json = Json.Null,
  ): Task[PlanState] =
    val deduplicated = plan.available.zipWithIndex.groupBy(_._1.expr).values.map: duplicates =>
      duplicates.maxBy((value, index) => (value.ordinal, index))._1
    .toVector.sortBy(value => (value.ordinal, value.path.pointer, value.exposedName))
    val initialOrdinal = math.max(plan.nextOrdinal, deduplicated.map(_.ordinal + 1).maxOption.getOrElse(plan.nextOrdinal))
    val initialQueue = newlyAvailable.groupBy(_.expr).values.map(_.maxBy(_.ordinal)).toVector.sortBy(_.ordinal)

    def loop(
      available: Vector[AvailableValue],
      queue: List[AvailableValue],
      nextOrdinal: Int,
    ): Task[(Vector[AvailableValue], Int)] = queue match
      case Nil => ZIO.succeed(available -> nextOrdinal)
      case source :: rest if !source.isArray => loop(available, rest, nextOrdinal)
      case source :: rest =>
        resolveHostValue(source.expr, values, input).either.flatMap:
          case Right(Json.Arr(items)) if items.size == 1 =>
            val indexed = source.schema.asObject
              .flatMap(_.get("items")).flatMap(_.asObject)
              .toVector.flatMap(SchemaModel.indexOutput)
            ZIO.foreach(indexed): value =>
              val expression = Expr.At(source.expr, 0, value.path.fields.toList)
              resolveHostValue(expression, values, input).option.map(_.map(_ => value -> expression))
            .map(_.flatten).flatMap: observed =>
              val existing = available.map(_.expr).toSet
              val fresh = observed.foldLeft(Vector.empty[AvailableValue]): (acc, entry) =>
                val (indexedValue, expression) = entry
                if existing.contains(expression) || acc.exists(_.expr == expression) then acc
                else
                  acc :+ AvailableValue(
                    expression,
                    indexedValue.schema,
                    indexedValue.exposedName,
                    SchemaModel.SchemaPath(source.path.fields ++ indexedValue.path.fields),
                    source.origin,
                    nextOrdinal + acc.size,
                    isArray = indexedValue.isArray,
                    fanOutSafety = FanOutSafety.Unsafe,
                  )
              loop(available ++ fresh, rest ++ fresh.filter(_.isArray).toList, nextOrdinal + fresh.size)
          case _ => loop(available, rest, nextOrdinal)

    loop(deduplicated, initialQueue.toList, initialOrdinal).map: (available, nextOrdinal) =>
      plan.copy(available = available, nextOrdinal = nextOrdinal)

  private def noPlanStateView(state: NoPlanState): Json.Obj = Json.Obj(
    "prompt" -> Json.Str(state.plan.prompt),
    "operationCatalog" -> Json.Arr(state.plan.catalog.map(GenericPlanner.decisionOperationView)*),
    "confirmedActions" -> Json.Arr(state.trace.map(Json.Str(_))*),
    "available" -> Json.Arr(state.plan.available.map(value => Json.Obj(
      "name" -> Json.Str(value.exposedName),
      "path" -> Json.Str(value.path.pointer),
      "isArray" -> Json.Bool(value.isArray),
    ))*),
    "evidence" -> Json.Arr(state.plan.evidence.map(ref => Json.Obj(
      "operation" -> Json.Str(ref.operation),
      "confirmed" -> Json.Bool(state.values.contains(ref.stepId)),
    ))*),
    "pendingFilterOutcome" -> state.pendingFilter.fold[Json](Json.Null)(pending => Json.Obj(
      "evaluation" -> pending.basis.fold[Json](Json.Null)(evaluationView),
      "synthesisRejection" -> pending.synthesisRejection.fold[Json](Json.Null)(rejectionView),
      "sourceCount" -> Json.Num(pending.parentSource.size),
      "attemptsUsed" -> Json.Num(pending.attemptsUsed),
    )),
    "lastActionFailure" -> state.lastActionFailure.fold[Json](Json.Null)(failure => Json.Obj(
      "operation" -> Json.Str(failure.operation),
      "mode" -> Json.Str(failure.mode),
      "message" -> Json.Str(failure.message),
    )),
  )

  private def executionPolicy(config: OrchestrationConfig): ExecutionPolicy = ExecutionPolicy(
    maxParallelism = config.maxParallelism,
    fanOutLimit = Some(config.hostGuard),
    maxFilterAttempts = config.maxFilterAttempts,
    maxRecoveries = config.maxRecoveries,
  )

  def execute(
    prompt: String,
    workflow: Workflow,
    operationInvoker: OperationInvoker,
    internalLlm: InternalLlmInvoker,
    config: OrchestrationConfig = OrchestrationConfig(),
  ): Task[(ExecutionReport, InternalLlmMetrics, String)] =
    for
      _ <- ZIO.fail(IllegalArgumentException("Workflow must contain a summarize generation step"))
        .unless(workflow.steps.exists {
          case Step.Generate(_, Catalog.SummarizeName, _) => true
          case _                                          => false
        })
      report <- WorkflowRuntime.execute(
        workflow,
        Json.Str(prompt),
        operationInvoker,
        internalLlm,
        executionPolicy(config),
      )
      text <- ZIO.fromOption(report.output.asString)
        .orElseFail(IllegalStateException(s"Summary workflow returned non-string output: ${report.output}"))
      metrics <- internalLlm.metrics
    yield (report, metrics, text)

  private def commonMetrics(
    mode: OrchestrationMode,
    turns: List[LoopTurn],
    usage: Usage,
    logicalTime: Long,
    physical: JevPhysicalMetrics,
    filterJev: JevFilterMetrics,
    llm: InternalLlmMetrics,
    mcp: McpPhysicalMetrics,
    execution: model.ExecutionMetrics,
    total: Long,
  ): OrchestrationMetrics = OrchestrationMetrics(
    mode,
    turns.size, usage.inputTokens, usage.outputTokens, logicalTime,
    physical.attempts, physical.successes, physical.failures, physical.timeMs,
    filterJev.calls, filterJev.inputTokens, filterJev.outputTokens, filterJev.timeMs,
    llm.extractionCount, llm.extractionInputTokens, llm.extractionOutputTokens, llm.extractionLatencyMs,
    llm.filterCount, llm.filterInputTokens, llm.filterOutputTokens, llm.filterLatencyMs,
    llm.summaryCount, llm.summaryInputTokens, llm.summaryOutputTokens, llm.summaryLatencyMs,
    mcp.calls, mcp.successes, mcp.failures, mcp.wallTimeMs, mcp.summedTimeMs,
    execution.recoveries,
    execution.replans,
    total,
  )
