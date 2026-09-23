package com.jamesward.zio_typesafe_ai.orchestration

import zio.*
import zio.json.ast.Json

object SchemaModel:
  case class SchemaPath(fields: Vector[String]):
    def /(field: String): SchemaPath = copy(fields = fields :+ field)
    def pointer: String = fields.map(f => "/" + f.replace("~", "~0").replace("/", "~1")).mkString

  case class IndexedValue(path: SchemaPath, schema: Json, exposedName: String, isArray: Boolean)
  case class ValidationError(path: String, message: String):
    override def toString: String = s"${if path.isEmpty then "/" else path}: $message"

  def properties(schema: Json.Obj): Vector[(String, Json)] =
    schema.get("properties").flatMap(_.asObject).fold(Vector.empty)(_.fields.toVector.sortBy(_._1))

  def required(schema: Json.Obj): Set[String] =
    schema.get("required").flatMap(_.asArray).fold(Set.empty[String])(_.flatMap(_.asString).toSet)

  def indexOutput(schema: Json.Obj): Vector[IndexedValue] =
    def loop(value: Json, path: SchemaPath, root: Json.Obj): Vector[IndexedValue] =
      resolve(value, root) match
        case obj: Json.Obj =>
          val kind = typeNames(obj) - "null"
          val current = path.fields.lastOption.toVector.flatMap: name =>
            if kind.contains("array") then Vector(IndexedValue(path, obj, name, true))
            else if kind.nonEmpty || obj.get("properties").nonEmpty then Vector(IndexedValue(path, obj, name, false))
            else Vector.empty
          if kind.contains("array") then current
          else current ++ properties(obj).flatMap((name, child) => loop(child, path / name, root))
        case _ => Vector.empty
    properties(schema).flatMap((name, child) => loop(child, SchemaPath(Vector(name)), schema))
      .sortBy(v => (v.path.fields.mkString("\u0000"), v.exposedName))

  def compatible(source: Json, target: Json, sourceRoot: Json.Obj, targetRoot: Json.Obj): Boolean =
    val left = resolve(source, sourceRoot)
    val right = resolve(target, targetRoot)
    (left.asObject, right.asObject) match
      case (Some(a), Some(b)) if hasUnsupportedUnion(a) || hasUnsupportedUnion(b) => false
      case (Some(a), Some(b)) =>
        val at = typeNames(a) - "null"
        val bt = typeNames(b) - "null"
        if at.size != 1 || bt.size != 1 || at != bt then false
        else at.head match
          case "object" =>
            val ap = properties(a).toMap
            properties(b).forall: (name, child) =>
              !required(b).contains(name) || ap.get(name).exists(compatible(_, child, sourceRoot, targetRoot))
          case "array" =>
            (a.get("items"), b.get("items")) match
              case (Some(ai), Some(bi)) => compatible(ai, bi, sourceRoot, targetRoot)
              case _                    => false
          case "string" | "boolean" | "number" | "integer" | "null" => true
          case _ => false
      case _ => false

  def projectRequired(schema: Json.Obj, names: Iterable[String]): Json.Obj =
    val selected = names.toVector.distinct.sorted
    val original = properties(schema).toMap
    val projected = selected.map(name => name -> original.getOrElse(name, Json.Obj()))
    val definitions = Vector("$defs", "definitions").flatMap(name => schema.get(name).map(name -> _))
    Json.Obj(Chunk.fromIterable(Vector(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(Chunk.fromIterable(projected)),
      "required" -> Json.Arr(Chunk.fromIterable(selected.map(Json.Str(_)))),
      "additionalProperties" -> Json.Bool(false),
    ) ++ definitions))

  def validate(value: Json, schema: Json.Obj): Either[Chunk[ValidationError], Unit] =
    val errors = validateAt(value, schema, schema, SchemaPath(Vector.empty), Set.empty)
    Either.cond(errors.isEmpty, (), Chunk.fromIterable(errors))

  def validateObject(value: Json.Obj, schema: Json.Obj): Either[Chunk[ValidationError], Unit] = validate(value, schema)

  private def validateAt(
    value: Json,
    rawSchema: Json,
    root: Json.Obj,
    path: SchemaPath,
    resolving: Set[String],
  ): Vector[ValidationError] =
    rawSchema.asObject match
      case None => Vector.empty
      case Some(schema) =>
        schema.get("$ref").flatMap(_.asString) match
          case Some(ref) if resolving.contains(ref) => Vector.empty
          case Some(ref) =>
            resolvePointer(root, ref) match
              case Some(target) => validateAt(value, target, root, path, resolving + ref)
              case None         => Vector(ValidationError(path.pointer, s"unresolved local schema reference '$ref'"))
          case None =>
            val allErrors = schema.get("allOf").flatMap(_.asArray).fold(Vector.empty[ValidationError])(
              _.toVector.flatMap(validateAt(value, _, root, path, resolving))
            )
            val anyErrors = schema.get("anyOf").flatMap(_.asArray).fold(Vector.empty[ValidationError]): alternatives =>
              if alternatives.exists(validateAt(value, _, root, path, resolving).isEmpty) then Vector.empty
              else Vector(ValidationError(path.pointer, "does not match any anyOf alternative"))
            val oneErrors = schema.get("oneOf").flatMap(_.asArray).fold(Vector.empty[ValidationError]): alternatives =>
              val matches = alternatives.count(validateAt(value, _, root, path, resolving).isEmpty)
              if matches == 1 then Vector.empty else Vector(ValidationError(path.pointer, s"matches $matches oneOf alternatives, expected exactly one"))
            val allowed = typeNames(schema)
            val typeErrors =
              if allowed.isEmpty || allowed.exists(matchesType(value, _)) then Vector.empty
              else Vector(ValidationError(path.pointer, s"expected type ${allowed.toVector.sorted.mkString("|")}"))
            val enumErrors = schema.get("enum").flatMap(_.asArray).fold(Vector.empty[ValidationError]): values =>
              if values.exists(_ == value) then Vector.empty else Vector(ValidationError(path.pointer, "value is not in enum"))
            val constErrors = schema.get("const").fold(Vector.empty[ValidationError]): expected =>
              if expected == value then Vector.empty else Vector(ValidationError(path.pointer, "value does not equal const"))
            val structural = if typeErrors.nonEmpty then Vector.empty else value match
              case obj: Json.Obj if allowed.contains("object") || schema.get("properties").nonEmpty =>
                val props = properties(schema).toMap
                val missing = required(schema).toVector.sorted.filterNot(obj.get(_).nonEmpty)
                  .map(name => ValidationError((path / name).pointer, "required property is missing"))
                val known = obj.fields.toVector.flatMap: (name, child) =>
                  props.get(name).toVector.flatMap(validateAt(child, _, root, path / name, resolving))
                val extras = obj.fields.toVector.collect:
                  case (name, _) if !props.contains(name) => name
                val extraErrors = schema.get("additionalProperties") match
                  case Some(Json.Bool(false)) => extras.map(name => ValidationError((path / name).pointer, "additional property is not allowed"))
                  case Some(extraSchema: Json.Obj) => obj.fields.toVector.flatMap: (name, child) =>
                    if props.contains(name) then Vector.empty else validateAt(child, extraSchema, root, path / name, resolving)
                  case _ => Vector.empty
                missing ++ known ++ extraErrors
              case Json.Arr(items) if allowed.contains("array") =>
                val cardinalityErrors = Vector(
                  schema.get("minItems").flatMap(_.asNumber).flatMap(n => scala.util.Try(n.value.intValueExact).toOption)
                    .filter(items.size < _).map(limit => ValidationError(path.pointer, s"array has ${items.size} items, below minItems $limit")),
                  schema.get("maxItems").flatMap(_.asNumber).flatMap(n => scala.util.Try(n.value.intValueExact).toOption)
                    .filter(items.size > _).map(limit => ValidationError(path.pointer, s"array has ${items.size} items, above maxItems $limit")),
                ).flatten
                cardinalityErrors ++ schema.get("items").toVector.flatMap(itemSchema =>
                  items.toVector.zipWithIndex.flatMap((item, index) => validateAt(item, itemSchema, root, path / index.toString, resolving))
                )
              case _ => Vector.empty
            allErrors ++ anyErrors ++ oneErrors ++ typeErrors ++ enumErrors ++ constErrors ++ structural

  private def typeNames(schema: Json.Obj): Set[String] = schema.get("type") match
    case Some(Json.Str(name)) => Set(name)
    case Some(Json.Arr(names)) => names.flatMap(_.asString).toSet
    case _ if schema.get("properties").nonEmpty => Set("object")
    case _ => Set.empty

  private def matchesType(value: Json, name: String): Boolean = name match
    case "null"    => value == Json.Null
    case "object"  => value.asObject.nonEmpty
    case "array"   => value.asArray.nonEmpty
    case "string"  => value.asString.nonEmpty
    case "boolean" => value.asBoolean.nonEmpty
    case "number"  => value.asNumber.nonEmpty
    case "integer" => value.asNumber.exists(number => number.value.stripTrailingZeros.scale <= 0)
    case _          => false

  private def hasUnsupportedUnion(schema: Json.Obj): Boolean =
    schema.get("oneOf").nonEmpty || schema.get("anyOf").nonEmpty || schema.get("allOf").nonEmpty || schema.get("not").nonEmpty

  private def resolve(schema: Json, root: Json.Obj): Json =
    schema.asObject.flatMap(_.get("$ref")).flatMap(_.asString).flatMap(resolvePointer(root, _)).getOrElse(schema)

  private def resolvePointer(root: Json.Obj, ref: String): Option[Json] =
    if !ref.startsWith("#/") then None
    else
      ref.drop(2).split('/').toList.map(_.replace("~1", "/").replace("~0", "~"))
        .foldLeft(Option(root: Json))((current, field) => current.flatMap(_.asObject).flatMap(_.get(field)))
