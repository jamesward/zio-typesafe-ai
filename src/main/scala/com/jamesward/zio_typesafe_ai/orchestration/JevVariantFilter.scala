package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.codec.json.schemaJson

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

case class JevFilterMetrics(
  calls: Int = 0,
  inputTokens: Int = 0,
  outputTokens: Int = 0,
  timeMs: Long = 0L,
)

final class JevFilterTracker private (ref: Ref[JevFilterMetrics]):
  def record(usage: Usage, elapsedMs: Long): UIO[Unit] =
    ref.update(current => current.copy(
      calls = current.calls + 1,
      inputTokens = current.inputTokens + usage.inputTokens,
      outputTokens = current.outputTokens + usage.outputTokens,
      timeMs = current.timeMs + elapsedMs,
    ))

  def metrics: UIO[JevFilterMetrics] = ref.get

object JevFilterTracker:
  def make: UIO[JevFilterTracker] = Ref.make(JevFilterMetrics()).map(new JevFilterTracker(_))

object JevVariantFilter:
  val MaxBatchSize = 255
  val MaxLlmEvidenceVariants = 255
  val RelevanceThreshold = 0.5

  enum Route:
    case Jev, Llm

  def route(variantCount: Int, threshold: Int): Route =
    if variantCount <= threshold then Route.Jev else Route.Llm

  case class Field(path: Vector[String], schema: Json.Obj):
    def pointer: String = "/" + path.mkString("/")

  case class Variant(id: String, projection: Json.Obj, fingerprint: String)
  case class Prepared(paths: Vector[Vector[String]], fields: Vector[Field], variants: Vector[Variant])
  case class Selection(predicate: PredicateFilter.Predicate, batchCount: Int)

  def prepare(source: Vector[Json], itemSchema: Json.Obj): Prepared =
    val paths = PredicateFilter.filterablePaths(itemSchema)
    val fields = paths.flatMap(path => schemaAt(itemSchema, path).map(Field(path, _)))
    val distinct = source.foldLeft(Vector.empty[Variant] -> Set.empty[String]):
      case ((variants, seen), item) =>
        val projection = PredicateFilter.projectVariant(item, paths)
        val fingerprint = projection.toJson
        if seen.contains(fingerprint) then variants -> seen
        else
          val id = f"v${variants.size}%03d"
          (variants :+ Variant(id, projection, fingerprint)) -> (seen + fingerprint)
    Prepared(paths, fields, distinct._1)

  def select(
    prompt: String,
    prepared: Prepared,
    client: Client,
    tracker: JevFilterTracker,
    maxParallelism: Int,
    prior: PredicateFilter.SynthesisRequest,
  ): Task[Selection] =
    for
      selectedFields <- selectFields(prompt, prepared, client, tracker)
      judgingPaths = if selectedFields.nonEmpty then selectedFields else prepared.paths
      batches = prepared.variants.grouped(MaxBatchSize).toVector
      recalledBatches <- ZIO.foreachPar(batches)(batch => classifyBatch(
        prompt,
        batch,
        judgingPaths,
        client,
        tracker,
        prior,
        "variant_recall",
        "Is this observed variant directly and specifically relevant to the complete user request? Reject variants that merely share a broad word or represent adjacent infrastructure.",
      )).withParallelism(maxParallelism)
      recalled = recalledBatches.flatten
      consolidate = batches.size > 1 && recalled.size > 1 && recalled.size <= MaxBatchSize
      consolidated <-
        if consolidate then
          classifyBatch(
            prompt,
            recalled,
            judgingPaths,
            client,
            tracker,
            prior,
            "variant_consolidation",
            "Evaluate each preliminary positive independently; do not retain only the single most central variant. Retain every variant whose displayed identity specifically denotes the requested subject, an explicitly named member or subtype of that subject, or an API contract, implementation, configuration, or result type specifically tied to it. Reject variants connected only by a generic term or adjacent subsystem.",
          )
        else ZIO.succeed(recalled)
      consolidatedFingerprints = consolidated.iterator.map(_.fingerprint).toSet
      retained =
        if consolidate then recalled.filter: candidate =>
          consolidatedFingerprints.contains(candidate.fingerprint) ||
            consolidated.exists(selected => isEnclosingIdentity(candidate, selected, prepared.paths))
        else recalled
      selected = retained.map(_.projection)
      calls = 1 + batches.size + (if consolidate then 1 else 0)
    yield Selection(PredicateFilter.variantMembership(prepared.paths, selected), calls)

  private def selectFields(
    prompt: String,
    prepared: Prepared,
    client: Client,
    tracker: JevFilterTracker,
  ): Task[Vector[Vector[String]]] =
    if prepared.fields.isEmpty then ZIO.succeed(Vector.empty)
    else
      val state: Json = Json.Obj(
        "prompt" -> Json.Str(prompt),
        "filterMode" -> Json.Str("field_relevance"),
        "fields" -> Json.Arr(prepared.fields.map(field => Json.Obj(
          "path" -> Json.Str(field.pointer),
          "type" -> field.schema.get("type").getOrElse(Json.Null),
          "description" -> field.schema.get("description").getOrElse(Json.Null),
        ))*),
      )
      val questions = prepared.fields.zipWithIndex.map: (field, index) =>
        QuestionId(s"item_$index") -> Question.Noul[Json](
          Json.Obj(
            "instruction" -> Json.Str("Is this field useful for deciding whether a record is directly relevant to the user's complete request?"),
            "candidateIndex" -> Json.Num(index),
          ),
          null,
        )
      runQuestions(state, questions, client, tracker).flatMap: answers =>
        ZIO.foreach(prepared.fields.zipWithIndex): (field, index) =>
          noul(answers, index).map(probability => Option.when(probability >= RelevanceThreshold)(field.path))
        .map(_.flatten)

  private def classifyBatch(
    prompt: String,
    variants: Vector[Variant],
    judgingPaths: Vector[Vector[String]],
    client: Client,
    tracker: JevFilterTracker,
    prior: PredicateFilter.SynthesisRequest,
    phase: String,
    instruction: String,
  ): Task[Vector[Variant]] =
    if variants.isEmpty then ZIO.succeed(Vector.empty)
    else
      val state: Json = Json.Obj(
        "prompt" -> Json.Str(prompt),
        "filterMode" -> Json.Str(phase),
        "priorOutcome" -> prior.outcomeDiagnostics.fold[Json](Json.Null)(diagnostic => Json.Obj(
          "classification" -> Json.Str(diagnostic.classification),
          "sourceCount" -> Json.Num(diagnostic.sourceCount),
          "matchCount" -> Json.Num(diagnostic.matchCount),
          "message" -> Json.Str(diagnostic.message),
        )),
        "variants" -> Json.Arr(variants.map(variant => Json.Obj(
          "id" -> Json.Str(variant.id),
          "values" -> projectProjection(variant.projection, judgingPaths),
        ))*),
      )
      val questions = variants.indices.toVector.map: index =>
        QuestionId(s"item_$index") -> Question.Noul[Json](
          Json.Obj("instruction" -> Json.Str(instruction), "candidateIndex" -> Json.Num(index)),
          null,
        )
      runQuestions(state, questions, client, tracker).flatMap: answers =>
        ZIO.foreach(variants.zipWithIndex): (variant, index) =>
          noul(answers, index).map(probability => Option.when(probability >= RelevanceThreshold)(variant))
        .map(_.flatten)

  private def runQuestions(
    state: Json,
    questions: Vector[(QuestionId, Question[?])],
    client: Client,
    tracker: JevFilterTracker,
  ): Task[Map[QuestionId, DynamicAnswer]] =
    for
      request <- ZIO.fromEither(TypeSafeAI.askDynamic(state, questions)).mapError(IllegalArgumentException(_))
      started <- Clock.nanoTime
      result <- request.run.provideEnvironment(ZEnvironment(client))
      finished <- Clock.nanoTime
      _ <- tracker.record(result.usage, (finished - started) / 1000000L)
    yield result.answers

  private def noul(answers: Map[QuestionId, DynamicAnswer], index: Int): Task[Double] =
    val id = QuestionId(s"item_$index")
    answers.get(id) match
      case Some(DynamicAnswer.Noul(probability)) => ZIO.succeed(probability.unwrap)
      case Some(other) => ZIO.fail(IllegalStateException(s"Expected Noul relevance answer for $id, got $other"))
      case None => ZIO.fail(IllegalStateException(s"Missing Noul relevance answer for $id"))

  private def projectProjection(projection: Json.Obj, paths: Vector[Vector[String]]): Json.Obj =
    val allowed = paths.map(path => "/" + path.mkString("/")).toSet
    Json.Obj(projection.fields.filter((name, _) => allowed.contains(name)))

  private def isEnclosingIdentity(
    candidate: Variant,
    selected: Variant,
    judgingPaths: Vector[Vector[String]],
  ): Boolean = judgingPaths.exists: path =>
    val pointer = "/" + path.mkString("/")
    (candidate.projection.get(pointer).flatMap(_.asString), selected.projection.get(pointer).flatMap(_.asString)) match
      case (Some(parent), Some(child)) if parent.length >= 3 && child.length > parent.length =>
        Vector(".", "$", "#").exists(separator => child.startsWith(parent + separator))
      case _ => false

  private def schemaAt(root: Json.Obj, path: Vector[String]): Option[Json.Obj] =
    path.foldLeft(Option(root)): (current, segment) =>
      current.flatMap(_.get("properties")).flatMap(_.asObject).flatMap(_.get(segment)).flatMap(_.asObject)

  def llmEvidence(prompt: String, sourceCount: Int, prepared: Prepared): PredicateFilter.SynthesisEvidence =
    val promptTerms = terms(prompt)
    val ranked = prepared.variants.map: variant =>
      val text = variant.projection.toJson.toLowerCase(Locale.ROOT)
      val score = promptTerms.count(term => text.contains(term) || (term.length >= 5 && text.contains(term.substring(0, 5))))
      (variant, score, digest(variant.fingerprint))
    val relevantBudget = (MaxLlmEvidenceVariants * 2) / 3
    val relevant = ranked.filter(_._2 > 0).sortBy(entry => (-entry._2, entry._3)).splitAt(relevantBudget)._1
    val selectedFingerprints = relevant.iterator.map(_._1.fingerprint).toSet
    val uniformBudget = MaxLlmEvidenceVariants - relevant.size
    val uniform = ranked.filterNot(entry => selectedFingerprints.contains(entry._1.fingerprint)).sortBy(_._3).splitAt(uniformBudget)._1
    val witnesses = (relevant ++ uniform).map(_._1.projection)
    PredicateFilter.SynthesisEvidence(sourceCount, prepared.variants.size, witnesses.size == prepared.variants.size,
      "prompt-linked-plus-stable-uniform", Json.Arr(witnesses*))

  private def terms(value: String): Vector[String] =
    value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+").toVector.filter(_.length >= 3).distinct

  private def digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
      .iterator.map(byte => f"${byte & 0xff}%02x").mkString
