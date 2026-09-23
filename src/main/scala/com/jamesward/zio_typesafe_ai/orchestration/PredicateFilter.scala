package com.jamesward.zio_typesafe_ai.orchestration

import zio.*
import zio.json.*
import zio.json.ast.Json
import scala.util.Try

/** A deliberately small predicate language. Paths are object-field segments only;
  * array indexes, JSONPath, regex, scripts, ranking and projection do not exist.
  * Boolean groups are unrolled to one level and bounded by MaxClauses. */
object PredicateFilter:
  val MaxPathDepth = 8
  val MaxClauses = 8

  enum Comparison:
    case Eq(value: Json)
    case In(values: Vector[Json])
    case Contains(value: String)
    case StartsWith(value: String)
    case Exists
    case IsNull
    case Gt(value: java.math.BigDecimal)
    case Gte(value: java.math.BigDecimal)
    case Lt(value: java.math.BigDecimal)
    case Lte(value: java.math.BigDecimal)

  case class Leaf(path: Vector[String], comparison: Comparison)

  enum Predicate:
    case Atom(value: Leaf)
    case All(values: Vector[Leaf])
    case Any(values: Vector[Leaf])
    case VariantMembership(paths: Vector[Vector[String]], fingerprints: Set[String])

  case class Diagnostic(classification: String, sourceCount: Int, matchCount: Int, message: String)

  enum SynthesisRejectionKind(val wireName: String):
    case InvalidPredicate extends SynthesisRejectionKind("invalid_predicate")
    case RepeatedPredicate extends SynthesisRejectionKind("repeated_predicate")

  object SynthesisRejectionKind:
    def fromWireName(value: String): Option[SynthesisRejectionKind] =
      SynthesisRejectionKind.values.find(_.wireName == value)

  val MaxSynthesisRejectionMessageLength = 240

  final class SynthesisRejection private (val kind: SynthesisRejectionKind, val message: String):
    override def equals(other: Any): Boolean = other match
      case that: SynthesisRejection => kind == that.kind && message == that.message
      case _                        => false
    override def hashCode: Int = 31 * kind.hashCode + message.hashCode
    override def toString: String = s"SynthesisRejection($kind,$message)"

  object SynthesisRejection:
    def apply(kind: SynthesisRejectionKind, message: String): SynthesisRejection =
      val compact = Option(message).getOrElse("")
        .map(ch => if Character.isISOControl(ch) || ch == '<' || ch == '>' then ' ' else ch)
        .mkString
        .split("\\s+")
        .filter(_.nonEmpty)
        .mkString(" ")
      val bounded = compact match
        case "" => "Predicate synthesis was rejected"
        case value if value.length > MaxSynthesisRejectionMessageLength =>
          value.substring(0, MaxSynthesisRejectionMessageLength - 1) + "…"
        case value => value
      new SynthesisRejection(kind, bounded)

    def unapply(value: SynthesisRejection): Option[(SynthesisRejectionKind, String)] =
      Some(value.kind -> value.message)

  enum SynthesisResult:
    case Accepted(canonical: Json.Obj)
    case Rejected(rejection: SynthesisRejection)

    /** Opaque host value carried through GenerativeInvoker. This is not the
      * model-facing predicate output schema. */
    def toGenerativeValue: Json.Obj = encodeSynthesisEnvelope(this)

  object SynthesisResult:
    def fromGenerativeValue(value: Json): Either[InvalidPredicate, SynthesisResult] =
      value.asObject.toRight(InvalidPredicate("Filter synthesis result must be an object"))
        .flatMap(decodeSynthesisEnvelope)

  case class SynthesisEvidence(
    sourceCount: Int,
    variantCount: Int,
    complete: Boolean,
    strategy: String,
    variants: Json.Arr,
  ):
    def toJson: Json.Obj = Json.Obj(
      "sourceCount" -> Json.Num(sourceCount),
      "variantCount" -> Json.Num(variantCount),
      "complete" -> Json.Bool(complete),
      "strategy" -> Json.Str(strategy),
      "variants" -> variants,
    )

  case class SynthesisRequest(
    prompt: String,
    itemSchema: Json.Obj,
    priorPredicate: Option[Json.Obj],
    outcomeDiagnostics: Option[Diagnostic],
    priorRejection: Option[SynthesisRejection] = None,
    evidence: Option[SynthesisEvidence] = None,
  ):
    /** Intentionally contains no host guard and no host max/limit setting. */
    def toJson: Json.Obj = Json.Obj(
      "prompt" -> Json.Str(prompt),
      "itemSchema" -> itemSchema,
      "priorPredicate" -> priorPredicate.getOrElse(Json.Null),
      "outcomeDiagnostics" -> outcomeDiagnostics.fold[Json](Json.Null)(d => Json.Obj(
        "classification" -> Json.Str(d.classification),
        "sourceCount" -> Json.Num(d.sourceCount),
        "matchCount" -> Json.Num(d.matchCount),
        "message" -> Json.Str(d.message),
      )),
      "priorRejection" -> priorRejection.fold[Json](Json.Null)(r => Json.Obj(
        "kind" -> Json.Str(r.kind.wireName),
        "message" -> Json.Str(r.message),
      )),
      "evidence" -> evidence.fold[Json](Json.Null)(_.toJson),
    )

  enum Classification:
    case Ready, TooBroad, NoMatches, NoProgress

  case class Evaluation(
    source: Vector[Json],
    matches: Vector[Json],
    predicate: Predicate,
    canonicalPredicate: Json.Obj,
    classification: Classification,
  )

  sealed trait FilterError extends Throwable
  case class InvalidPredicate(message: String) extends FilterError:
    override def getMessage: String = message
  case class RepeatedPredicate(canonical: Json.Obj) extends FilterError:
    override def getMessage: String = s"Repeated filter predicate: ${canonical.toJson}"

  private val scalarValue = Json.Obj(
    "description" -> Json.Str("A scalar JSON string, number, boolean, or null; objects and arrays are rejected by the host."),
  )
  private val path = Json.Obj(
    "type" -> Json.Str("array"),
    "items" -> Json.Obj("type" -> Json.Str("string")),
    "minItems" -> Json.Num(1),
    "maxItems" -> Json.Num(MaxPathDepth),
    "description" -> Json.Str(s"Object field segments only (1 to $MaxPathDepth). No indexes or JSONPath syntax."),
  )
  private val leafSchema = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj(
      "op" -> Json.Obj("type" -> Json.Str("string"), "enum" -> Json.Arr(
        Vector("eq", "in", "contains", "startsWith", "exists", "isNull", "gt", "gte", "lt", "lte").map(Json.Str(_))*
      )),
      "path" -> path,
      "value" -> scalarValue,
      "values" -> Json.Obj(
        "type" -> Json.Str("array"), "items" -> scalarValue,
        "minItems" -> Json.Num(1), "maxItems" -> Json.Num(MaxClauses),
      ),
    ),
    "required" -> Json.Arr(Json.Str("op"), Json.Str("path")),
    "additionalProperties" -> Json.Bool(false),
  )

  /** Runtime Tool.dynamic output schema. Host parsing below supplies the
    * conditional strictness that JSON-schema subsets often cannot express. */
  val outputSchema: Json.Obj = Json.Obj(
    "type" -> Json.Str("object"),
    "properties" -> Json.Obj(
      "kind" -> Json.Obj("type" -> Json.Str("string"), "enum" -> Json.Arr(Json.Str("atom"), Json.Str("all"), Json.Str("any"))),
      "leaf" -> leafSchema,
      "clauses" -> Json.Obj(
        "type" -> Json.Str("array"), "items" -> leafSchema,
        "minItems" -> Json.Num(1), "maxItems" -> Json.Num(MaxClauses),
      ),
    ),
    "required" -> Json.Arr(Json.Str("kind")),
    "additionalProperties" -> Json.Bool(false),
  )

  private val EnvelopeType = "filter_synthesis_result_v1"
  private val VariantMembershipType = "filter_variant_membership_v1"

  private def encodeSynthesisEnvelope(result: SynthesisResult): Json.Obj = result match
    case SynthesisResult.Accepted(canonical) => Json.Obj(
      "_hostType" -> Json.Str(EnvelopeType),
      "status" -> Json.Str("accepted"),
      "criteria" -> canonical,
    )
    case SynthesisResult.Rejected(rejection) => Json.Obj(
      "_hostType" -> Json.Str(EnvelopeType),
      "status" -> Json.Str("rejected"),
      "rejection" -> Json.Obj(
        "kind" -> Json.Str(rejection.kind.wireName),
        "message" -> Json.Str(rejection.message),
      ),
    )

  private def decodeSynthesisEnvelope(value: Json.Obj): Either[InvalidPredicate, SynthesisResult] =
    if !value.get("_hostType").flatMap(_.asString).contains(EnvelopeType) then
      Left(InvalidPredicate("Unrecognized filter synthesis result envelope"))
    else value.get("status").flatMap(_.asString) match
      case Some("accepted") =>
        value.get("criteria").flatMap(_.asObject)
          .map(SynthesisResult.Accepted(_))
          .toRight(InvalidPredicate("Accepted filter synthesis result requires object criteria"))
      case Some("rejected") =>
        for
          rejection <- value.get("rejection").flatMap(_.asObject)
            .toRight(InvalidPredicate("Rejected filter synthesis result requires rejection details"))
          kindName <- rejection.get("kind").flatMap(_.asString)
            .toRight(InvalidPredicate("Synthesis rejection requires string kind"))
          kind <- SynthesisRejectionKind.fromWireName(kindName)
            .toRight(InvalidPredicate(s"Unknown synthesis rejection kind '$kindName'"))
          message <- rejection.get("message").flatMap(_.asString)
            .toRight(InvalidPredicate("Synthesis rejection requires string message"))
        yield SynthesisResult.Rejected(SynthesisRejection(kind, message))
      case Some(other) => Left(InvalidPredicate(s"Unknown synthesis result status '$other'"))
      case None        => Left(InvalidPredicate("Filter synthesis result requires string status"))


  def projectVariant(item: Json, paths: Vector[Vector[String]]): Json.Obj =
    val fields = paths.sortBy(_.mkString("/")).flatMap: path =>
      val value = path.foldLeft(Option(item))((current, segment) => current.flatMap(_.asObject).flatMap(_.get(segment)))
      value.filter(isScalar).map(found => ("/" + path.mkString("/")) -> normalizeStringScalar(found))
    Json.Obj(fields*)

  def variantMembership(paths: Vector[Vector[String]], selected: Vector[Json.Obj]): Predicate =
    Predicate.VariantMembership(paths, selected.iterator.map(_.toJson).toSet)

  private def decodeVariantMembership(value: Json.Obj, itemSchema: Json.Obj): Either[InvalidPredicate, Predicate] =
    for
      rawPaths <- value.get("paths").flatMap(_.asArray).toRight(InvalidPredicate("Variant membership requires paths"))
      paths <- rawPaths.toVector.foldLeft[Either[InvalidPredicate, Vector[Vector[String]]]](Right(Vector.empty)):
        case (acc, rawPath) => for
          parsed <- acc
          segments <- rawPath.asArray.toRight(InvalidPredicate("Variant membership paths must be arrays"))
          path <- segments.toVector.foldLeft[Either[InvalidPredicate, Vector[String]]](Right(Vector.empty)):
            case (pathAcc, segment) => for
              current <- pathAcc
              text <- segment.asString.toRight(InvalidPredicate("Variant membership path segments must be strings"))
            yield current :+ text
        yield parsed :+ path
      _ <- Either.cond(paths.nonEmpty && paths.forall(filterablePaths(itemSchema).contains), (), InvalidPredicate("Variant membership contains undeclared paths"))
      rawFingerprints <- value.get("fingerprints").flatMap(_.asArray).toRight(InvalidPredicate("Variant membership requires fingerprints"))
      fingerprints <- rawFingerprints.toVector.foldLeft[Either[InvalidPredicate, Set[String]]](Right(Set.empty)):
        case (acc, raw) => for
          parsed <- acc
          fingerprint <- raw.asString.toRight(InvalidPredicate("Variant membership fingerprints must be strings"))
        yield parsed + fingerprint
    yield Predicate.VariantMembership(paths, fingerprints)
  /** Accepts raw predicate criteria and host-created synthesis envelopes. The
    * internal LLM boundary uses parseCriteriaAndValidate directly, so an
    * untrusted model cannot create a trusted host envelope. */
  def parseAndValidate(value: Json.Obj, itemSchema: Json.Obj): Either[InvalidPredicate, Predicate] =
    value.get("_hostType").flatMap(_.asString) match
      case Some(VariantMembershipType) => decodeVariantMembership(value, itemSchema)
      case Some(EnvelopeType) =>
        decodeSynthesisEnvelope(value).flatMap:
          case SynthesisResult.Accepted(criteria) => parseCriteriaAndValidate(criteria, itemSchema)
          case SynthesisResult.Rejected(rejection) => Left(InvalidPredicate(rejection.message))
      case Some(other) => Left(InvalidPredicate(s"Unrecognized host filter criterion '$other'"))
      case None        => parseCriteriaAndValidate(value, itemSchema)

  private[orchestration] def parseCriteriaAndValidate(value: Json.Obj, itemSchema: Json.Obj): Either[InvalidPredicate, Predicate] =
    def invalid(message: String) = Left(InvalidPredicate(message))
    value.get("kind").flatMap(_.asString) match
      case Some("atom") =>
        if value.fields.map(_._1).toSet != Set("kind", "leaf") then invalid("atom requires exactly kind and leaf")
        else value.get("leaf").flatMap(_.asObject).toRight(InvalidPredicate("atom.leaf must be an object"))
          .flatMap(parseLeaf(_, itemSchema)).map(Predicate.Atom(_))
      case Some(kind @ ("all" | "any")) =>
        if value.fields.map(_._1).toSet != Set("kind", "clauses") then invalid(s"$kind requires exactly kind and clauses")
        else value.get("clauses").flatMap(_.asArray).toRight(InvalidPredicate(s"$kind.clauses must be an array")).flatMap: clauses =>
          if clauses.isEmpty || clauses.size > MaxClauses then invalid(s"$kind.clauses must contain 1 to $MaxClauses leaves")
          else
            clauses.toVector.foldLeft[Either[InvalidPredicate, Vector[Leaf]]](Right(Vector.empty)) { (acc, raw) =>
              for
                parsed <- acc
                obj <- raw.asObject.toRight(InvalidPredicate(s"$kind clauses must be leaf objects"))
                leaf <- parseLeaf(obj, itemSchema)
              yield parsed :+ leaf
            }.map(values => if kind == "all" then Predicate.All(values) else Predicate.Any(values))
      case Some(other) => invalid(s"Unknown predicate kind '$other'")
      case None        => invalid("Predicate requires string kind")

  /** Declared scalar object-field paths accepted by predicate validation.
    * Arrays are terminal and are never traversed, even when their item schema is
    * an object. The result is stable and conservative. */
  def filterablePaths(itemSchema: Json.Obj): Vector[Vector[String]] =
    def scalarTypes(schema: Json.Obj): Set[String] = schema.get("type") match
      case Some(Json.Str(name))  => Set(name)
      case Some(Json.Arr(names)) => names.flatMap(_.asString).toSet
      case _                     => Set.empty

    def loop(schema: Json.Obj, prefix: Vector[String]): Vector[Vector[String]] =
      if prefix.size >= MaxPathDepth then Vector.empty
      else
        schema.get("properties").flatMap(_.asObject).fold(Vector.empty): properties =>
          properties.fields.toVector.sortBy(_._1).flatMap: (name, rawChild) =>
            rawChild.asObject.toVector.flatMap: child =>
              val path = prefix :+ name
              val types = scalarTypes(child)
              if types.nonEmpty && types.subsetOf(Set("string", "number", "integer", "boolean", "null")) then Vector(path)
              else if types.contains("array") then Vector.empty
              else loop(child, path)
    loop(itemSchema, Vector.empty)

  private def parseLeaf(value: Json.Obj, itemSchema: Json.Obj): Either[InvalidPredicate, Leaf] =
    val allowed = Set("op", "path", "value", "values")
    val extras = value.fields.map(_._1).toSet -- allowed
    for
      _ <- Either.cond(extras.isEmpty, (), InvalidPredicate(s"Unsupported predicate fields: ${extras.toVector.sorted.mkString(",")}"))
      op <- value.get("op").flatMap(_.asString).toRight(InvalidPredicate("leaf.op must be a string"))
      rawPath <- value.get("path").flatMap(_.asArray).toRight(InvalidPredicate("leaf.path must be an array"))
      segments <- rawPath.toVector.foldLeft[Either[InvalidPredicate, Vector[String]]](Right(Vector.empty)): (acc, raw) =>
        for
          path <- acc
          segment <- raw.asString.toRight(InvalidPredicate("path segments must be strings"))
          _ <- Either.cond(safeSegment(segment), (), InvalidPredicate(s"Unsafe path segment '$segment'"))
        yield path :+ segment
      _ <- Either.cond(segments.nonEmpty && segments.size <= MaxPathDepth, (), InvalidPredicate(s"path must contain 1 to $MaxPathDepth segments"))
      target <- schemaAt(itemSchema, segments).toRight(InvalidPredicate(s"path /${segments.mkString("/")} is not a declared scalar item-schema path"))
      comparison <- parseComparison(op, value, target)
    yield Leaf(segments, comparison)

  private def safeSegment(value: String): Boolean =
    value.nonEmpty && value.length <= 128 && value != "." && value != ".." &&
      !value.exists(ch => ch == '/' || ch == '[' || ch == ']' || ch == '$' || ch == '*' || ch == '~')

  private def schemaAt(root: Json.Obj, path: Vector[String]): Option[Json.Obj] =
    path.foldLeft(Option(root)): (current, name) =>
      current.flatMap(_.get("properties")).flatMap(_.asObject).flatMap(_.get(name)).flatMap(_.asObject)
    .filter: schema =>
      val types = schema.get("type") match
        case Some(Json.Str(name)) => Set(name)
        case Some(Json.Arr(names)) => names.flatMap(_.asString).toSet
        case _ => Set.empty[String]
      types.nonEmpty && types.subsetOf(Set("string", "number", "integer", "boolean", "null"))

  private def parseComparison(op: String, obj: Json.Obj, schema: Json.Obj): Either[InvalidPredicate, Comparison] = {
    val types = schema.get("type") match {
      case Some(Json.Str(name)) => Set(name)
      case Some(Json.Arr(values)) => values.flatMap(_.asString).toSet
      case _ => Set.empty[String]
    }
    def scalar(name: String): Either[InvalidPredicate, Json] = obj.get(name).filter(isScalar).toRight(InvalidPredicate(s"$op requires scalar '$name'"))
    def only(fields: Set[String]): Either[InvalidPredicate, Unit] =
      Either.cond(obj.fields.map(_._1).toSet == fields + "op" + "path", (), InvalidPredicate(s"$op has invalid or missing fields"))
    def number: Either[InvalidPredicate, java.math.BigDecimal] = scalar("value").flatMap(_.asNumber.map(_.value).toRight(InvalidPredicate(s"$op.value must be numeric")))
    op match {
      case "eq" =>
        only(Set("value")).flatMap(_ => scalar("value")).flatMap(v =>
          Either.cond(matchesScalarSchema(v, types), Comparison.Eq(v), InvalidPredicate("eq value does not match path schema"))
        )
      case "in" =>
        for {
          _ <- only(Set("values"))
          values <- obj.get("values").flatMap(_.asArray).toRight(InvalidPredicate("in requires values array"))
          vector = values.toVector
          _ <- Either.cond(vector.nonEmpty && vector.size <= MaxClauses && vector.forall(v => isScalar(v) && matchesScalarSchema(v, types)), (), InvalidPredicate(s"in requires 1 to $MaxClauses schema-compatible scalars"))
        } yield Comparison.In(vector.distinct)
      case "contains" | "startsWith" =>
        for {
          _ <- only(Set("value"))
          _ <- Either.cond(types.contains("string"), (), InvalidPredicate(s"$op requires a string schema path"))
          text <- obj.get("value").flatMap(_.asString).toRight(InvalidPredicate(s"$op.value must be a string"))
        } yield if op == "contains" then Comparison.Contains(text) else Comparison.StartsWith(text)
      case "exists" | "isNull" => only(Set.empty).map(_ => if op == "exists" then Comparison.Exists else Comparison.IsNull)
      case "gt" | "gte" | "lt" | "lte" =>
        for {
          _ <- only(Set("value"))
          _ <- Either.cond(types.exists(Set("number", "integer")), (), InvalidPredicate(s"$op requires a numeric schema path"))
          n <- number
        } yield op match {
          case "gt"  => Comparison.Gt(n)
          case "gte" => Comparison.Gte(n)
          case "lt"  => Comparison.Lt(n)
          case _     => Comparison.Lte(n)
        }
      case other => Left(InvalidPredicate(s"Unsupported operator '$other'"))
    }
  }

  private def isScalar(value: Json): Boolean = value match
    case _: Json.Str | _: Json.Num | _: Json.Bool | Json.Null => true
    case _ => false

  private def matchesScalarSchema(value: Json, types: Set[String]): Boolean = value match
    case Json.Null    => types.contains("null")
    case _: Json.Str  => types.contains("string")
    case _: Json.Bool => types.contains("boolean")
    case n: Json.Num  => types.contains("number") || (types.contains("integer") && n.value.stripTrailingZeros.scale <= 0)
    case _            => false

  def canonical(predicate: Predicate): Json.Obj = predicate match
    case Predicate.Atom(value) => Json.Obj("kind" -> Json.Str("atom"), "leaf" -> canonicalLeaf(value))
    case Predicate.All(values) =>
      val clauses = values.map(canonicalLeaf).sortBy(_.toJson)
      Json.Obj("kind" -> Json.Str("all"), "clauses" -> Json.Arr(clauses*))
    case Predicate.Any(values) =>
      val clauses = values.map(canonicalLeaf).sortBy(_.toJson)
      Json.Obj("kind" -> Json.Str("any"), "clauses" -> Json.Arr(clauses*))
    case Predicate.VariantMembership(paths, fingerprints) => Json.Obj(
      "_hostType" -> Json.Str(VariantMembershipType),
      "paths" -> Json.Arr(paths.sortBy(_.mkString("/")).map(path => Json.Arr(path.map(Json.Str(_))*))*),
      "fingerprints" -> Json.Arr(fingerprints.toVector.sorted.map(Json.Str(_))*),
    )

  private def canonicalLeaf(leaf: Leaf): Json.Obj =
    val base = Vector("op" -> Json.Str(opName(leaf.comparison)), "path" -> Json.Arr(leaf.path.map(Json.Str(_))*))
    val extra = leaf.comparison match
      case Comparison.Eq(v)          => Vector("value" -> normalizeStringScalar(v))
      case Comparison.In(vs)         => Vector("values" -> Json.Arr(vs.map(normalizeStringScalar).distinct.sortBy(_.toJson)*))
      case Comparison.Contains(v)    => Vector("value" -> Json.Str(v.toLowerCase(java.util.Locale.ROOT)))
      case Comparison.StartsWith(v)  => Vector("value" -> Json.Str(v.toLowerCase(java.util.Locale.ROOT)))
      case Comparison.Gt(v)          => Vector("value" -> Json.Num(v))
      case Comparison.Gte(v)         => Vector("value" -> Json.Num(v))
      case Comparison.Lt(v)          => Vector("value" -> Json.Num(v))
      case Comparison.Lte(v)         => Vector("value" -> Json.Num(v))
      case Comparison.Exists | Comparison.IsNull => Vector.empty
    Json.Obj(Chunk.fromIterable(base ++ extra))

  private def opName(comparison: Comparison): String = comparison match
    case Comparison.Eq(_)         => "eq"
    case Comparison.In(_)         => "in"
    case Comparison.Contains(_)   => "contains"
    case Comparison.StartsWith(_) => "startsWith"
    case Comparison.Exists        => "exists"
    case Comparison.IsNull        => "isNull"
    case Comparison.Gt(_)         => "gt"
    case Comparison.Gte(_)        => "gte"
    case Comparison.Lt(_)         => "lt"
    case Comparison.Lte(_)        => "lte"

  def evaluate(predicate: Predicate, item: Json): Boolean = predicate match
    case Predicate.Atom(value) => evaluateLeaf(value, item)
    case Predicate.All(values) => values.forall(evaluateLeaf(_, item))
    case Predicate.Any(values) => values.exists(evaluateLeaf(_, item))
    case Predicate.VariantMembership(paths, fingerprints) => fingerprints.contains(projectVariant(item, paths).toJson)

  private def normalizeStringScalar(value: Json): Json = value match
    case Json.Str(text) => Json.Str(text.toLowerCase(java.util.Locale.ROOT))
    case other          => other

  private def scalarEquals(left: Json, right: Json): Boolean = (left, right) match
    case (Json.Str(a), Json.Str(b)) => a.equalsIgnoreCase(b)
    case _                          => left == right

  private def evaluateLeaf(leaf: Leaf, item: Json): Boolean =
    val found = leaf.path.foldLeft(Option(item: Json))((current, name) => current.flatMap(_.asObject).flatMap(_.get(name)))
    leaf.comparison match
      case Comparison.Exists => found.nonEmpty
      case Comparison.IsNull => found.contains(Json.Null)
      case Comparison.Eq(expected) => found.exists(scalarEquals(_, expected))
      case Comparison.In(values) => found.exists(actual => values.exists(scalarEquals(actual, _)))
      case Comparison.Contains(value) => found.flatMap(_.asString).exists(_.toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT)))
      case Comparison.StartsWith(value) => found.flatMap(_.asString).exists(_.toLowerCase(java.util.Locale.ROOT).startsWith(value.toLowerCase(java.util.Locale.ROOT)))
      case Comparison.Gt(value) => found.flatMap(_.asNumber).exists(_.value.compareTo(value) > 0)
      case Comparison.Gte(value) => found.flatMap(_.asNumber).exists(_.value.compareTo(value) >= 0)
      case Comparison.Lt(value) => found.flatMap(_.asNumber).exists(_.value.compareTo(value) < 0)
      case Comparison.Lte(value) => found.flatMap(_.asNumber).exists(_.value.compareTo(value) <= 0)

  /** Evaluates every item before classifying. Source and matches are complete
    * vectors in original source order. For refinement, source is the complete
    * prior match set and strict reduction is required. */
  def classify(
    source: Vector[Json],
    predicate: Predicate,
    hostGuard: Int,
    priorMatchCount: Option[Int] = None,
  ): Either[InvalidPredicate, Evaluation] =
    if hostGuard <= 0 then Left(InvalidPredicate("host guard must be positive"))
    else
      val matches = source.filter(evaluate(predicate, _))
      val classification =
        if matches.isEmpty then Classification.NoMatches
        else if priorMatchCount.exists(matches.size >= _) then Classification.NoProgress
        else if matches.size > hostGuard then Classification.TooBroad
        else Classification.Ready
      Right(Evaluation(source, matches, predicate, canonical(predicate), classification))

  def staticArrayBound(schema: Json.Obj): Option[Int] =
    schema.get("maxItems").flatMap(_.asNumber).flatMap(number => Try(number.value.intValueExact).toOption).filter(_ >= 0)
