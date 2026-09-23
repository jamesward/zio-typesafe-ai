package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.orchestration.runtime.GenerativeInvoker
import zio.*
import zio.json.*
import zio.json.ast.Json
import scala.util.Try


case class ExtractionRequest(
  prompt: String,
  targetTool: String,
  targetDescription: String,
  targetInputSchema: Json.Obj,
  outputSchema: Json.Obj,
  knownArguments: Json.Obj,
  priorContext: Json.Arr,
  missingFields: Vector[String],
)

case class LlmResult[+A](value: A, inputTokens: Int = 0, outputTokens: Int = 0, latencyMs: Long = 0L)

trait LlmTransport:
  def extract(request: ExtractionRequest): Task[LlmResult[Json.Obj]]
  def synthesizeFilter(request: PredicateFilter.SynthesisRequest): Task[LlmResult[Json.Obj]] =
    ZIO.fail(UnsupportedOperationException("synthesize_filter is not implemented by this transport"))
  def summarize(prompt: String, evidence: Json.Arr): Task[LlmResult[String]]

case class InternalLlmMetrics(
  extractionCount: Int = 0,
  extractionInputTokens: Int = 0,
  extractionOutputTokens: Int = 0,
  extractionLatencyMs: Long = 0L,
  filterCount: Int = 0,
  filterInputTokens: Int = 0,
  filterOutputTokens: Int = 0,
  filterLatencyMs: Long = 0L,
  summaryCount: Int = 0,
  summaryInputTokens: Int = 0,
  summaryOutputTokens: Int = 0,
  summaryLatencyMs: Long = 0L,
):
  def totalTokens: Int = extractionInputTokens + extractionOutputTokens + filterInputTokens + filterOutputTokens + summaryInputTokens + summaryOutputTokens

final class InternalLlmInvoker private (
  transport: LlmTransport,
  metricsRef: Ref[InternalLlmMetrics],
) extends GenerativeInvoker:
  def metrics: UIO[InternalLlmMetrics] = metricsRef.get

  def generate(operation: String, arguments: Json.Obj): Task[Json] = operation match
    case Catalog.ExtractName => extract(arguments)
    case Catalog.FilterName => synthesizeFilter(arguments)
    case Catalog.SummarizeName => summarize(arguments)
    case other => ZIO.fail(IllegalArgumentException(s"Unknown internal generative operation '$other'"))

  private def extract(arguments: Json.Obj): Task[Json] = for
    prompt <- string(arguments, "prompt")
    targetTool <- string(arguments, "targetTool")
    description <- string(arguments, "targetDescription")
    targetSchema <- obj(arguments, "targetInputSchema")
    outputSchema <- obj(arguments, "outputSchema")
    known <- obj(arguments, "knownArguments")
    context <- array(arguments, "priorContext")
    missing <- array(arguments, "missingFields").flatMap(values =>
      ZIO.foreach(values.elements)(value => ZIO.fromOption(value.asString).orElseFail(IllegalArgumentException("missingFields must contain strings"))).map(_.toVector)
    )
    result <- transport.extract(ExtractionRequest(prompt, targetTool, description, targetSchema, outputSchema, known, context, missing))
    _ <- ZIO.fromEither(SchemaModel.validateObject(result.value, outputSchema)).mapError(errors =>
      IllegalArgumentException(s"Extracted arguments for '$targetTool' failed local schema validation: ${errors.mkString("; ")}")
    )
    _ <- metricsRef.update(metrics => metrics.copy(
      extractionCount = metrics.extractionCount + 1,
      extractionInputTokens = metrics.extractionInputTokens + result.inputTokens,
      extractionOutputTokens = metrics.extractionOutputTokens + result.outputTokens,
      extractionLatencyMs = metrics.extractionLatencyMs + result.latencyMs,
    ))
  yield result.value

  private def synthesizeFilter(arguments: Json.Obj): Task[Json] = for
    prompt <- string(arguments, "prompt")
    itemSchema <- obj(arguments, "itemSchema")
    prior = arguments.get("priorPredicate").flatMap(_.asObject)
    diagnostics <- arguments.get("outcomeDiagnostics").flatMap(_.asObject) match
      case None => ZIO.none
      case Some(value) =>
        (for
          classification <- string(value, "classification")
          sourceCount <- integer(value, "sourceCount")
          matchCount <- integer(value, "matchCount")
          message <- string(value, "message")
        yield Some(PredicateFilter.Diagnostic(classification, sourceCount, matchCount, message)))
    priorRejection <- arguments.get("priorRejection").flatMap(_.asObject) match
      case None => ZIO.none
      case Some(value) =>
        for
          kindName <- string(value, "kind")
          kind <- ZIO.fromOption(PredicateFilter.SynthesisRejectionKind.fromWireName(kindName))
            .orElseFail(IllegalArgumentException(s"Unknown synthesis rejection kind '$kindName'"))
          message <- string(value, "message")
        yield Some(PredicateFilter.SynthesisRejection(kind, message))
    evidence <- arguments.get("evidence") match
      case None | Some(Json.Null) => ZIO.none
      case Some(raw) =>
        for
          value <- ZIO.fromOption(raw.asObject).orElseFail(IllegalArgumentException("Filter evidence must be an object"))
          sourceCount <- integer(value, "sourceCount")
          variantCount <- integer(value, "variantCount")
          complete <- ZIO.fromOption(value.get("complete").flatMap(_.asBoolean)).orElseFail(IllegalArgumentException("Filter evidence requires boolean 'complete'"))
          strategy <- string(value, "strategy")
          variants <- array(value, "variants")
        yield Some(PredicateFilter.SynthesisEvidence(sourceCount, variantCount, complete, strategy, variants))
    request = PredicateFilter.SynthesisRequest(prompt, itemSchema, prior, diagnostics, priorRejection, evidence)
    result <- transport.synthesizeFilter(request)
    _ <- metricsRef.update(metrics => metrics.copy(
      filterCount = metrics.filterCount + 1,
      filterInputTokens = metrics.filterInputTokens + result.inputTokens,
      filterOutputTokens = metrics.filterOutputTokens + result.outputTokens,
      filterLatencyMs = metrics.filterLatencyMs + result.latencyMs,
    ))
    synthesis = PredicateFilter.parseCriteriaAndValidate(result.value, itemSchema) match
      case Right(predicate) => PredicateFilter.SynthesisResult.Accepted(PredicateFilter.canonical(predicate))
      case Left(error) => PredicateFilter.SynthesisResult.Rejected(PredicateFilter.SynthesisRejection(
        PredicateFilter.SynthesisRejectionKind.InvalidPredicate,
        error.getMessage,
      ))
  yield synthesis.toGenerativeValue

  private def summarize(arguments: Json.Obj): Task[Json] = for
    prompt <- string(arguments, "prompt")
    evidence <- array(arguments, "evidence")
    result <- transport.summarize(prompt, evidence)
    _ <- metricsRef.update(metrics => metrics.copy(
      summaryCount = metrics.summaryCount + 1,
      summaryInputTokens = metrics.summaryInputTokens + result.inputTokens,
      summaryOutputTokens = metrics.summaryOutputTokens + result.outputTokens,
      summaryLatencyMs = metrics.summaryLatencyMs + result.latencyMs,
    ))
  yield Json.Obj("summary" -> Json.Str(result.value))

  private def string(obj: Json.Obj, name: String): Task[String] =
    ZIO.fromOption(obj.get(name).flatMap(_.asString)).orElseFail(IllegalArgumentException(s"Internal operation requires string '$name'"))
  private def obj(value: Json.Obj, name: String): Task[Json.Obj] =
    ZIO.fromOption(value.get(name).flatMap(_.asObject)).orElseFail(IllegalArgumentException(s"Internal operation requires object '$name'"))
  private def integer(value: Json.Obj, name: String): Task[Int] =
    ZIO.fromOption(value.get(name).flatMap(_.asNumber).flatMap(n => Try(n.value.intValueExact).toOption))
      .orElseFail(IllegalArgumentException(s"Internal operation requires integer '$name'"))
  private def array(value: Json.Obj, name: String): Task[Json.Arr] =
    ZIO.fromOption(value.get(name).flatMap(_.asArray).map(Json.Arr(_))).orElseFail(IllegalArgumentException(s"Internal operation requires array '$name'"))

object InternalLlmInvoker:
  def make(transport: LlmTransport): UIO[InternalLlmInvoker] =
    Ref.make(InternalLlmMetrics()).map(new InternalLlmInvoker(transport, _))

