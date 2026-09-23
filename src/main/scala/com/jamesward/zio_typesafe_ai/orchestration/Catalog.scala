package com.jamesward.zio_typesafe_ai.orchestration

import zio.*
import zio.json.*
import zio.json.ast.Json

enum CapabilityKind:
  case Mcp, ExtractToolArguments, SynthesizeFilter, Summarize

case class OperationSpec(
  name: String,
  description: String,
  inputSchema: Json.Obj,
  outputSchema: Json.Obj,
  kind: CapabilityKind,
):
  def toJson: Json.Obj = Json.Obj(
    "name" -> Json.Str(name),
    "description" -> Json.Str(description),
    "kind" -> Json.Str(kind.toString),
    "inputSchema" -> inputSchema,
    "outputSchema" -> outputSchema,
  )

object Catalog:
  val ExtractName = "extract_tool_arguments"
  val FilterName = "synthesize_filter"
  val SummarizeName = "summarize"

  private val string = Json.Obj("type" -> Json.Str("string"))
  private val anyObject = Json.Obj("type" -> Json.Str("object"))

  val extract: OperationSpec = OperationSpec(
    ExtractName,
    "Internal LLM capability inserted by the host to supply only required arguments that cannot be bound exactly from prior typed outputs.",
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(
        "prompt" -> string,
        "targetTool" -> string,
        "targetDescription" -> string,
        "targetInputSchema" -> anyObject,
        "outputSchema" -> anyObject,
        "knownArguments" -> anyObject,
        "priorContext" -> Json.Obj("type" -> Json.Str("array")),
        "missingFields" -> Json.Obj("type" -> Json.Str("array"), "items" -> string),
      ),
      "required" -> Json.Arr(
        Json.Str("prompt"), Json.Str("targetTool"), Json.Str("targetDescription"),
        Json.Str("targetInputSchema"), Json.Str("outputSchema"), Json.Str("knownArguments"),
        Json.Str("priorContext"), Json.Str("missingFields"),
      ),
      "additionalProperties" -> Json.Bool(false),
    ),
    anyObject,
    CapabilityKind.ExtractToolArguments,
  )

  val synthesizeFilter: OperationSpec = OperationSpec(
    FilterName,
    "Internal LLM capability that synthesizes one host-validated schema-safe predicate. Host cardinality guards are never disclosed.",
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(
        "prompt" -> string,
        "itemSchema" -> anyObject,
        "priorPredicate" -> anyObject,
        "outcomeDiagnostics" -> anyObject,
      ),
      "required" -> Json.Arr(Json.Str("prompt"), Json.Str("itemSchema")),
      "additionalProperties" -> Json.Bool(false),
    ),
    PredicateFilter.outputSchema,
    CapabilityKind.SynthesizeFilter,
  )

  val summarize: OperationSpec = OperationSpec(
    SummarizeName,

    "Internal LLM capability that produces the final answer from the user prompt and accumulated untrusted operation evidence.",
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(
        "prompt" -> string,
        "evidence" -> Json.Obj("type" -> Json.Str("array")),
      ),
      "required" -> Json.Arr(Json.Str("prompt"), Json.Str("evidence")),
      "additionalProperties" -> Json.Bool(false),
    ),
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj("summary" -> string),
      "required" -> Json.Arr(Json.Str("summary")),
      "additionalProperties" -> Json.Bool(false),
    ),
    CapabilityKind.Summarize,
  )

  /** Build the optional host-input surface from external operation parameters.
    * Conflicting schemas for one field are retained as a deterministic anyOf. */
  def initialInputSchema(catalog: Vector[OperationSpec]): Json.Obj =
    val fields = catalog.flatMap: operation =>
      operation.kind match
        case CapabilityKind.Mcp => SchemaModel.properties(operation.inputSchema).map: (name, schema) =>
          (name, schema, operation)
        case _ => Vector.empty
    val properties = fields.groupBy(_._1).toVector.sortBy(_._1).map: (name, entries) =>
      val variants = entries.map(_._2).distinctBy(_.toJson).sortBy(_.toJson)
      val acceptedBy = entries.map(_._3).distinctBy(_.name).sortBy(_.name)
        .map(operation => s"${operation.name}: ${operation.description}").mkString("; ")
      val fieldDescriptions = variants.flatMap(_.asObject.flatMap(_.get("description")).flatMap(_.asString)).distinct
      val description = Json.Str((fieldDescriptions :+ s"Accepted by provided operations: $acceptedBy").mkString(" "))
      val schema = variants match
        case Vector(single) => single.asObject match
          case Some(obj) => Json.Obj(obj.fields.filterNot(_._1 == "description") :+ ("description" -> description))
          case None      => Json.Obj("anyOf" -> Json.Arr(single), "description" -> description)
        case multiple => Json.Obj("anyOf" -> Json.Arr(multiple*), "description" -> description)
      name -> schema
    Json.Obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(properties*),
      "additionalProperties" -> Json.Bool(false),
    )

  def toJson(catalog: Vector[OperationSpec]): Json.Arr = Json.Arr(catalog.map(_.toJson)*)
