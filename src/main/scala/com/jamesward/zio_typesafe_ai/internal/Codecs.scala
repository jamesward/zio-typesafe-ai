package com.jamesward.zio_typesafe_ai.internal

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.schema.Schema
import zio.schema.codec.JsonCodec

import java.nio.charset.StandardCharsets.UTF_8

/**
 * Codec-level helpers shared across the library — the encode/decode
 * boundary between `zio-schema`'s `Schema[A]` (what every caller-supplied
 * `state` / `instructions` / criteria description is described by) and
 * `zio-json`'s `Json` AST (what `Content` stores, and what `Helpers`
 * assembles the request body out of).
 *
 * `state` / `instructions` / criteria descriptions are genuinely
 * heterogeneous — different questions in the same request can be
 * described by different `Schema`-having types — so *something* has to
 * represent "an already-JSON-shaped value of statically unknown
 * origin" wherever they're combined. `zio.schema.DynamicValue` (with
 * `@directDynamicMapping`) is `zio-schema`'s own answer to that; this
 * library uses `zio.json.ast.Json` instead, because the outer wire
 * request also needs to be *assembled* (nested objects/arrays, question
 * ids as keys) — real work `Json.Obj` / `Json.Arr` plus `zio-json`'s own
 * tested printer already do correctly, without a hand-rolled string
 * escaper.
 *
 * The two libraries' codecs don't talk to each other directly, so the
 * bridge below round-trips through UTF-8 text: `Schema[A]` encodes to
 * valid JSON bytes (`zio-schema-json`), which `zio-json`'s own parser
 * reads into a `Json` node it can then compose with others.
 */
private[zio_typesafe_ai] object Codecs:

  /** Drop absent optional fields and empty collections from the wire
    * rather than emitting `null` / `[]` / `{}`. Only applies to the
    * response side now — `Answer` / `Usage` / `SystemOneResponse` are
    * the only remaining `derives Schema` wire types; the request side
    * is assembled by hand via [[toJsonAst]] / [[fromJsonAst]]. */
  val codecConfig: JsonCodec.Configuration =
    JsonCodec.Configuration(
      explicitEmptyCollections = JsonCodec.ExplicitConfig(encoding = false, decoding = false),
      explicitNulls            = JsonCodec.ExplicitConfig(encoding = false, decoding = false),
    )

  /** Encode `value` via its own `Schema[A]`, then parse the result with
    * `zio-json`'s parser into a `Json` node. The `Left` case is
    * unreachable in practice — `schemaBasedBinaryCodec` always emits
    * valid JSON — so it's surfaced as a defect: a failure here is a bug
    * in this bridge, not a caller error. */
  def toJsonAst[A](value: A)(using schema: Schema[A]): Json =
    val bytes = JsonCodec.schemaBasedBinaryCodec[A](codecConfig).encode(value)
    new String(bytes.toArray, UTF_8).fromJson[Json].fold(
      err  => throw new IllegalStateException(s"schema-encoded value failed to parse as JSON (this is a bug in Codecs.toJsonAst): $err"),
      json => json,
    )

  /** Decode a `Json` node via a `Schema[A]`, by rendering it back to
    * text with `zio-json`'s printer and decoding that with
    * `zio-schema-json`. */
  def fromJsonAst[A](json: Json)(using schema: Schema[A]): Either[String, A] =
    JsonCodec.schemaBasedBinaryCodec[A](codecConfig)
      .decode(Chunk.fromArray(json.toJson.getBytes(UTF_8)))
      .left.map(_.message)
