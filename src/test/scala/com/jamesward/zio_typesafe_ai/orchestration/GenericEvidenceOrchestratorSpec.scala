package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.{AppTypeSafeAIMock, TypeSafeAI}
import com.jamesward.zio_typesafe_ai.orchestration.model.*
import com.jamesward.zio_typesafe_ai.orchestration.runtime.OperationInvoker
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object GenericEvidenceOrchestratorSpec extends ZIOSpecDefault:
  private val string = Json.Obj("type" -> Json.Str("string"))
  private val inputSchema = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj("query" -> string),
    "required" -> Json.Arr(Json.Str("query")),
    "additionalProperties" -> Json.Bool(false),
  )
  private val itemSchema = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj("id" -> string),
    "required" -> Json.Arr(Json.Str("id")),
    "additionalProperties" -> Json.Bool(false),
  )
  private val enumerateOutput = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj("items" -> Json.Obj("type" -> Json.Str("array"), "items" -> itemSchema)),
    "required" -> Json.Arr(Json.Str("items")),
    "additionalProperties" -> Json.Bool(false),
  )
  private val detailInput = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj("id" -> string),
    "required" -> Json.Arr(Json.Str("id")),
    "additionalProperties" -> Json.Bool(false),
  )
  private val detailOutput = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj("value" -> string),
    "required" -> Json.Arr(Json.Str("value")),
    "additionalProperties" -> Json.Bool(false),
  )
  private val catalog = Vector(
    OperationSpec("enumerate", "enumerate records for a query", inputSchema, enumerateOutput, CapabilityKind.Mcp),
    OperationSpec("inspect", "inspect one record", detailInput, detailOutput, CapabilityKind.Mcp),
    Catalog.extract,
    Catalog.synthesizeFilter,
    Catalog.summarize,
  )
  private val source = Vector.tabulate(3)(index => Json.Obj("id" -> Json.Str(s"item-$index")))

  def spec = suite("generic evidence-only orchestration")(
    test("derives optional initial input solely from external operation schemas") {
      val derived = Catalog.initialInputSchema(catalog)
      val properties = derived.get("properties").flatMap(_.asObject)
      assertTrue(
        properties.exists(_.fields.map(_._1).toSet == Set("id", "query")),
        derived.get("required").isEmpty,
        derived.get("additionalProperties").flatMap(_.asBoolean).contains(false),
        properties.flatMap(_.get("query")).flatMap(_.asObject).flatMap(_.get("description")).flatMap(_.asString)
          .exists(text => text.contains("enumerate") && text.contains("enumerate records for a query")),
        properties.flatMap(_.get("id")).flatMap(_.asObject).flatMap(_.get("description")).flatMap(_.asString)
          .exists(text => text.contains("inspect") && text.contains("inspect one record")),
        !derived.toJson.contains("prompt"),
        !derived.toJson.contains("itemSchema"),
      )
    },
    test("uses exact initial input, Jev filtering, and a structured evidence terminal") {
      import AppTypeSafeAIMock.ScriptedResponse
      for
        observed <- AppTypeSafeAIMock.scriptedObserved(
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Nouls(Vector(0.9)),
          ScriptedResponse.Nouls(Vector(0.1, 0.9, 0.1)),
          ScriptedResponse.Choice("c000"),
          ScriptedResponse.Choice("c000"),
        )
        calls <- Ref.make(Vector.empty[(String, Json.Obj)])
        result <- GenericOrchestrator.runEvidence(
          "inspect the relevant record",
          catalog,
          Json.Obj("query" -> Json.Str("target")),
          inputSchema,
          new OperationInvoker:
            def call(operation: String, arguments: Json.Obj) =
              calls.update(_ :+ (operation -> arguments)) *> (operation match
                case "enumerate" => ZIO.succeed(Json.Obj("items" -> Json.Arr(source*)))
                case "inspect" => ZIO.succeed(Json.Obj(
                  "value" -> Json.Str(s"detail:${arguments.get("id").flatMap(_.asString).getOrElse("")}"),
                ))
                case other => ZIO.fail(IllegalStateException(s"unexpected operation $other")))
          ,
          OrchestrationConfig(jevFilterVariantThreshold = 10, maxOperations = 2, maxIterations = 4),
        ).provideLayer(observed.layer)
        invoked <- calls.get
      yield assertTrue(
        invoked.map(_._1) == Vector("enumerate", "inspect"),
        invoked.headOption.flatMap(_._2.get("query")).flatMap(_.asString).contains("target"),
        invoked.lift(1).flatMap(_._2.get("id")).flatMap(_.asString).contains("item-1"),
        result.evidence.elements.size == 2,
        result.evidence.toJson.contains("HostFiltered"),
        result.evidence.toJson.contains("detail:item-1"),
        result.metrics.jevFilterCalls == 2,
        result.metrics.extractCalls == 0,
        result.metrics.filterCalls == 0,
        result.metrics.summaryCalls == 0,
        result.actionTrace.lastOption.contains("return-evidence"),
        !result.workflow.steps.exists(_.isInstanceOf[Step.Generate]),
      )
    },
    test("LLM filter mode synthesizes criteria without extraction or summarization") {
      val criterion = Json.Obj(
        "kind" -> Json.Str("atom"),
        "leaf" -> Json.Obj(
          "op" -> Json.Str("eq"),
          "path" -> Json.Arr(Json.Str("id")),
          "value" -> Json.Str("item-1"),
        ),
      )
      for
        observed <- AppTypeSafeAIMock.scriptedObserved(
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
        )
        filterLlm <- InternalLlmInvoker.make(new LlmTransport:
          def extract(request: ExtractionRequest) = ZIO.dieMessage("extraction must not run")
          override def synthesizeFilter(request: PredicateFilter.SynthesisRequest) =
            ZIO.succeed(LlmResult(criterion, inputTokens = 7, outputTokens = 3))
          def summarize(prompt: String, evidence: Json.Arr) = ZIO.dieMessage("summary must not run")
        )
        result <- GenericOrchestrator.runEvidence(
          "inspect the relevant record",
          catalog,
          Json.Obj("query" -> Json.Str("target")),
          inputSchema,
          new OperationInvoker:
            def call(operation: String, arguments: Json.Obj) = operation match
              case "enumerate" => ZIO.succeed(Json.Obj("items" -> Json.Arr(source*)))
              case "inspect" => ZIO.succeed(Json.Obj("value" -> Json.Str("detail:item-1")))
              case other => ZIO.fail(IllegalStateException(s"unexpected operation $other"))
          ,
          config = OrchestrationConfig(jevFilterVariantThreshold = 10, maxOperations = 2, maxIterations = 4),
          filterMode = EvidenceFilterMode.Llm,
          filterLlm = Some(filterLlm),
        ).provideLayer(observed.layer)
      yield assertTrue(
        result.evidence.toJson.contains("detail:item-1"),
        result.metrics.jevFilterCalls == 0,
        result.metrics.extractCalls == 0,
        result.metrics.filterCalls == 1,
        result.metrics.filterInputTokens == 7,
        result.metrics.filterOutputTokens == 3,
        result.metrics.summaryCalls == 0,
        result.actionTrace.exists(_.startsWith("filter-engine:llm:")),
        result.actionTrace.lastOption.contains("return-evidence"),
      )
    },
    test("retires an empty filter source without model calls or recovery loops") {
      for
        observed <- AppTypeSafeAIMock.scriptedObserved(
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
          AppTypeSafeAIMock.ScriptedResponse.Choice("c000"),
        )
        result <- GenericOrchestrator.runEvidence(
          "inspect matching records",
          catalog,
          Json.Obj("query" -> Json.Str("none")),
          inputSchema,
          new OperationInvoker:
            def call(operation: String, arguments: Json.Obj) = operation match
              case "enumerate" => ZIO.succeed(Json.Obj("items" -> Json.Arr()))
              case other       => ZIO.fail(IllegalStateException(s"unexpected operation $other"))
          ,
          OrchestrationConfig(jevFilterVariantThreshold = 10, maxOperations = 2, maxIterations = 3),
        ).provideLayer(observed.layer)
      yield assertTrue(
        result.metrics.jevFilterCalls == 0,
        result.metrics.filterCalls == 0,
        result.metrics.recoveries == 0,
        result.actionTrace.contains("filter-skipped:empty"),
        result.actionTrace.contains("filter-outcome:NoMatches:0/0:attempt=0"),
        result.actionTrace.lastOption.contains("return-evidence"),
      )
    },
    test("rejects invalid initial input before any operation or Jev request") {
      for
        observed <- AppTypeSafeAIMock.choicesObserved("c000")
        calls <- Ref.make(0)
        exit <- GenericOrchestrator.runEvidence(
          "inspect",
          catalog,
          Json.Obj(),
          inputSchema,
          new OperationInvoker:
            def call(operation: String, arguments: Json.Obj) = calls.update(_ + 1) *> ZIO.succeed(Json.Obj())
          ,
        ).provideLayer(observed.layer).exit
        invoked <- calls.get
        requests <- observed.requestBodies
      yield assertTrue(exit.isFailure, invoked == 0, requests.isEmpty)
    },
  )
