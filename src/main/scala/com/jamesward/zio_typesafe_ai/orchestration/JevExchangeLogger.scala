package com.jamesward.zio_typesafe_ai.orchestration

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import zio.*
import zio.json.*
import zio.json.ast.Json
import scala.annotation.targetName

/** Redacts TypeSafe AI exchanges to the metadata needed to understand Jev decisions.
  *
  * This deliberately rebuilds known wire shapes instead of recursively deleting known
  * sensitive keys. Consequently, new and malformed fields are omitted by default.
  */
object JevExchangeLogger:

  /** Compact a TypeSafe AI request without retaining schemas or runtime values. */
  def compactRequest(request: Json): Json =
    request.asObject.fold[Json](Json.Obj()): root =>
      val filterMode = root.get("state").flatMap(_.asObject).flatMap(_.get("filterMode")).flatMap(_.asString)
      val internalFilter = filterMode.exists(mode => mode == "field_relevance" || mode.startsWith("variant_"))
      obj(
        field(root, "model", scalar),
        field(root, "state", compactState),
        if internalFilter then root.get("questions").flatMap(_.asObject).map(questions =>
          "questions" -> Json.Obj("count" -> Json.Num(questions.fields.size))
        ) else field(root, "questions", compactQuestions),
      )

  /** Compact a canonical TypeSafe AI response while retaining its complete answer data. */
  def compactResponse(response: Json): Json =
    response.asObject.fold[Json](Json.Obj()): root =>
      obj(
        field(root, "model", scalar),
        field(root, "answers", compactAnswers),
        field(root, "usage", compactUsage),
      )

  /** One-line, compact exchange logging suitable for normal orchestration runs. */
  val compact: TypeSafeAI.ExchangeObserver = TypeSafeAI.ExchangeObserver.fromFunction: observation =>
    val request = compactRequest(observation.request).toJson
    observation.outcome match
      case TypeSafeAI.ExchangeOutcome.Success(response) =>
        ZIO.logInfo(
          s"Jev compact exchange request=$request; response=${compactObservedResponse(observation.request, response).toJson}; latencyMs=${observation.latencyMs}"
        )
      case TypeSafeAI.ExchangeOutcome.Failure(cause) =>
        ZIO.logError(
          s"Jev compact exchange request=$request; failure=${failureSummary(cause)}; latencyMs=${observation.latencyMs}"
        )

  private def compactObservedResponse(request: Json, response: Json): Json =
    val filterMode = request.asObject.flatMap(_.get("state")).flatMap(_.asObject)
      .flatMap(_.get("filterMode")).flatMap(_.asString)
    val internalFilter = filterMode.exists(mode => mode == "field_relevance" || mode.startsWith("variant_"))
    if !internalFilter then compactResponse(response)
    else response.asObject.fold[Json](Json.Obj()): root =>
      val answers = root.get("answers").flatMap(_.asObject).map(_.fields.map(_._2)).getOrElse(Chunk.empty)
      val selected = answers.count(answer => answer.asObject.flatMap(_.get("noul")).flatMap(_.asNumber)
        .exists(_.value.doubleValue >= JevVariantFilter.RelevanceThreshold))
      obj(
        field(root, "model", scalar),
        Some("answers" -> Json.Obj(
          "count" -> Json.Num(answers.size),
          "selectedCount" -> Json.Num(selected),
        )),
        field(root, "usage", compactUsage),
      )
  /** Select full-body logging only when the caller has explicitly approved it. */
  def selected(fullBodies: Boolean): TypeSafeAI.ExchangeObserver =
    if fullBodies then TypeSafeAI.ExchangeObserver.logging else compact

  private def compactState(value: Json): Json =
    value.asObject.fold[Json](Json.Obj()): state =>
      obj(
        field(state, "prompt", scalar),
        field(state, "goal", scalar),
        field(state, "filterMode", scalar),
        arrayCountField(state, "variants", "variantsCount"),
        arrayCountField(state, "fields", "fieldsCount"),
        field(state, "operationCatalog", catalog),
        field(state, "confirmedActions", stringArray),
        field(state, "available", availableValues),
        field(state, "evidence", evidenceValues),
        field(state, "pendingFilterOutcome", compactPendingFilter),
        field(state, "lastActionFailure", compactActionFailure),
        field(state, "partialWorkflow", compactPartialWorkflow),
        field(state, "evidenceCount", scalar),
      )

  private def compactPartialWorkflow(value: Json): Json =
    value.asArray.fold[Json](Json.Obj()): steps =>
      Json.Obj("steps" -> Json.Arr(steps.map(compactStep)*))

  private def compactStep(value: Json): Json =
    value.asObject.fold[Json](Json.Obj()): step =>
      obj(
        field(step, "id", scalar),
        field(step, "kind", scalar),
        field(step, "operation", compactNamedValue),
      )

  private def catalog(value: Json): Json =
    array(value): entry =>
      entry.asObject.map(operation).getOrElse(Json.Obj())

  private def operation(value: Json.Obj): Json.Obj =
    obj(
      field(value, "name", scalar),
      field(value, "kind", scalar),
      field(value, "description", scalar),
      field(value, "inputSchema", compactSchemaSummary),
      field(value, "outputSchema", compactSchemaSummary),
    )

  private def compactSchemaSummary(value: Json): Json =
    value.asObject.fold[Json](Json.Obj()): schema =>
      val required = schema.get("required").flatMap(_.asArray).toVector.flatten.flatMap(_.asString).toSet
      val fields = schema.get("properties").flatMap(_.asObject).toVector.flatMap(_.fields).map: (name, raw) =>
        raw.asObject.fold[Json](Json.Obj("name" -> Json.Str(name))): property =>
          obj(
            Some("name" -> Json.Str(name)),
            field(property, "type", compactType),
            field(property, "description", scalar),
            Some("required" -> Json.Bool(required.contains(name))),
          )
      obj(
        field(schema, "description", scalar),
        Some("fields" -> Json.Arr(fields*)),
      )

  private def compactType(value: Json): Json = value match
    case value @ Json.Str(_) => value
    case Json.Arr(values)    => Json.Arr(values.flatMap(_.asString.map(Json.Str(_)))* )
    case _                   => Json.Str("complex")

  private def availableValues(value: Json): Json =
    array(value): entry =>
      entry.asObject.fold[Json](Json.Obj()): available =>
        obj(
          field(available, "name", scalar),
          field(available, "path", scalar),
          field(available, "isArray", scalar),
        )

  private def evidenceValues(value: Json): Json =
    array(value): entry =>
      entry.asObject.fold[Json](Json.Obj()): evidence =>
        obj(
          field(evidence, "operation", compactNamedValue),
          field(evidence, "confirmed", scalar),
        )

  private def compactPendingFilter(value: Json): Json = value match
    case Json.Null => Json.Null
    case _ =>
      value.asObject.fold[Json](Json.Obj()): pending =>
        obj(
          field(pending, "evaluation", compactEvaluation),
          field(pending, "synthesisRejection", compactSynthesisRejection),
          field(pending, "attemptsUsed", scalar),
        )

  private def compactEvaluation(value: Json): Json = value match
    case Json.Null => Json.Null
    case _ => value.asObject.fold[Json](Json.Obj()): evaluation =>
      obj(
        field(evaluation, "classification", scalar),
        field(evaluation, "sourceCount", scalar),
        field(evaluation, "matchCount", scalar),
        field(evaluation, "predicate", compactPredicate),
      )

  private def compactSynthesisRejection(value: Json): Json = value match
    case Json.Null => Json.Null
    case _ => value.asObject.fold[Json](Json.Obj()): rejection =>
      obj(
        field(rejection, "kind", scalar),
        field(rejection, "message", scalar),
      )

  private def compactActionFailure(value: Json): Json = value match
    case Json.Null => Json.Null
    case _ => value.asObject.fold[Json](Json.Obj()): failure =>
      obj(
        field(failure, "operation", compactNamedValue),
        field(failure, "mode", scalar),
        field(failure, "message", scalar),
      )

  private def compactQuestions(value: Json): Json =
    dynamicObject(value): question =>
      question.asObject.fold[Json](Json.Obj()): body =>
        val criteria = body.get("type").flatMap(_.asString).flatMap:
          case "choice" => field(body, "criteria", compactChoiceOptions)
          case "noul"   => field(body, "criteria", compactNoulCriteria)
          case "score"  => field(body, "criteria", compactScoreCriteria)
          case _        => None
        obj(
          field(body, "type", scalar),
          field(body, "instructions", compactInstructions),
          criteria,
        )

  private def compactInstructions(value: Json): Option[Json] = value match
    case text @ Json.Str(_) => Some(text)
    case _ => value.asObject.map: instructions =>
        obj(
          field(instructions, "instruction", scalar),
          field(instructions, "candidateIndex", _.asNumber),
        )

  private def compactNoulCriteria(value: Json): Json =
    value.asObject.fold[Json](Json.Obj()): criteria =>
      obj(
        field(criteria, "true", criteriaScalar),
        field(criteria, "false", criteriaScalar),
      )

  private def compactScoreCriteria(value: Json): Json =
    value.asArray.fold[Json](Json.Arr()): criteria =>
      Json.Arr(criteria.map(entry => criteriaScalar(entry).getOrElse(Json.Obj()))*)

  private def criteriaScalar(value: Json): Option[Json] = value match
    case value @ (Json.Str(_) | Json.Num(_) | Json.Bool(_) | Json.Null) => Some(value)
    case _ => None

  private def compactChoiceOptions(value: Json): Json =
    dynamicObject(value)(compactChoiceOption)

  private def compactChoiceOption(value: Json): Json = value match
    case Json.Null => Json.Null
    case _ =>
      value.asObject.fold[Json](Json.Obj()): option =>
        obj(
          field(option, "candidateId", scalar),
          field(option, "id", scalar),
          field(option, "transition", scalar),
          field(option, "action", scalar),
          field(option, "operation", compactNamedValue),
          field(option, "capability", compactNamedValue),
          field(option, "mode", scalar),
          field(option, "source", compactSource),
          field(option, "exactBindings", compactBindings),
          field(option, "missingRequiredArguments", stringArray),
          field(option, "hostWillInsertExtraction", scalar),
          field(option, "outcomeDiagnostics", compactDiagnostics),
          field(option, "priorPredicate", compactPredicate),
          field(option, "synthesisRejection", compactSynthesisRejection),
          field(option, "nextAttempt", scalar),
          field(option, "evidenceCount", scalar),
        )

  private def compactNamedValue(value: Json): Json = value match
    case value @ Json.Str(_) => value
    case _ => value.asObject.fold[Json](Json.Obj())(operation)

  private def compactSource(value: Json): Json = value match
    case Json.Null => Json.Null
    case _ =>
      value.asObject.fold[Json](Json.Obj()): source =>
        obj(
          field(source, "origin", scalar),
          field(source, "input", scalar),
          field(source, "ref", scalar),
          field(source, "path", compactSourcePath),
          field(source, "filterablePaths", stringArray),
          field(source, "itemPath", stringArray),
        )

  private def compactSourcePath(value: Json): Option[Json] = value match
    case value @ Json.Str(_) => Some(value)
    case Json.Arr(_)         => Some(stringArray(value))
    case _                   => None

  private def compactBindings(value: Json): Json = value match
    case Json.Obj(fields) => Json.Obj(fields.flatMap: (name, origin) =>
        origin.asString.map(value => name -> Json.Str(if value == "item" || value.startsWith("item:") then "item" else "checkpoint"))
      )
    case Json.Arr(values) => Json.Arr(values.flatMap: binding =>
        binding.asString.map(Json.Str(_)).orElse(binding.asObject.flatMap(_.get("name")).flatMap(_.asString).map(Json.Str(_)))
      *)
    case _ => Json.Obj()

  private def compactDiagnostics(value: Json): Json =
    value.asObject.fold[Json](Json.Obj()): diagnostics =>
      obj(
        field(diagnostics, "classification", scalar),
        field(diagnostics, "sourceCount", scalar),
        field(diagnostics, "matchCount", scalar),
        field(diagnostics, "message", scalar),
      )

  private def compactPredicate(value: Json): Json = value match
    case Json.Null => Json.Null
    case _ => value.asObject.fold[Json](Json.Obj()): predicate =>
      if predicate.get("kind").flatMap(_.asString).contains("variant_membership") then
        obj(
          field(predicate, "kind", scalar),
          field(predicate, "paths", value => array(value)(stringArray)),
          field(predicate, "selectedCount", scalar),
        )
      else obj(
        field(predicate, "kind", scalar),
        field(predicate, "leaf", compactLeaf),
        field(predicate, "clauses", value => array(value)(compactLeaf)),
      )

  private def compactLeaf(value: Json): Json =
    value.asObject.fold[Json](Json.Obj()): leaf =>
      obj(
        field(leaf, "op", scalar),
        field(leaf, "path", stringArray),
        field(leaf, "value", scalar),
        field(leaf, "values", scalarArray),
      )

  private def compactAnswers(value: Json): Json =
    dynamicObject(value): answer =>
      answer.asObject.fold[Json](Json.Obj()): body =>
        body.get("type").flatMap(_.asString) match
          case Some("noul") => obj(
              field(body, "type", scalar),
              field(body, "noul", scalar),
            )
          case Some("choice") => obj(
              field(body, "type", scalar),
              field(body, "choice", scalar),
              field(body, "probabilities", numericMap),
              field(body, "confidence", scalar),
            )
          case Some("score") => obj(
              field(body, "type", scalar),
              field(body, "score", scalar),
              field(body, "legend", stringMap),
              field(body, "probabilities", numericMap),
              field(body, "confidence", scalar),
            )
          case _ => obj(field(body, "type", scalar))

  private def compactUsage(value: Json): Json =
    value.asObject.fold[Json](Json.Obj()): usage =>
      obj(
        field(usage, "input_tokens", scalar),
        field(usage, "output_tokens", scalar),
      )

  private def numericMap(value: Json): Json = mapOf(value)(_.asNumber)
  private def stringMap(value: Json): Json = mapOf(value)(_.asString.map(Json.Str(_)))

  private def mapOf(value: Json)(keep: Json => Option[Json]): Json =
    value.asObject.fold[Json](Json.Obj()): values =>
      Json.Obj(values.fields.flatMap((key, item) => keep(item).map(key -> _)))

  private def stringArray(value: Json): Json =
    value.asArray.fold[Json](Json.Arr()): values =>
      Json.Arr(values.flatMap(_.asString.map(Json.Str(_)))* )

  private def scalarArray(value: Json): Json =
    value.asArray.fold[Json](Json.Arr()): values =>
      Json.Arr(values.flatMap(scalar)*)

  private def scalar(value: Json): Option[Json] = value match
    case value @ (Json.Str(_) | Json.Num(_) | Json.Bool(_) | Json.Null) => Some(value)
    case _ => None

  private def array(value: Json)(compact: Json => Json): Json =
    value.asArray.fold[Json](Json.Arr())(values => Json.Arr(values.map(compact)*))

  private def dynamicObject(value: Json)(compact: Json => Json): Json =
    value.asObject.fold[Json](Json.Obj()): values =>
      Json.Obj(values.fields.map((key, item) => key -> compact(item)))

  private def arrayCountField(source: Json.Obj, inputName: String, outputName: String): Option[(String, Json)] =
    source.get(inputName).flatMap(_.asArray).map(values => outputName -> Json.Num(values.size))

  private def field(source: Json.Obj, name: String, compact: Json => Json): Option[(String, Json)] =
    source.get(name).map(value => name -> compact(value))

  @targetName("optionalField")
  private def field(source: Json.Obj, name: String, compact: Json => Option[Json]): Option[(String, Json)] =
    source.get(name).flatMap(compact).map(name -> _)

  private def obj(fields: Option[(String, Json)]*): Json.Obj = Json.Obj(fields.flatten*)

  private def failureSummary(cause: Cause[TypeSafeAI.Error]): String =
    val text = cause.failureOption
      .map(error => s"${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("")}")
      .orElse(cause.dieOption.map(error => s"${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("")}"))
      .getOrElse("interrupted")
    Json.Str(text.replaceAll("\\s+", " ")).toJson
