package com.jamesward.zio_typesafe_ai.internal

import zio.json.ast.Json
import zio.schema.annotation.{caseName, discriminatorName, fieldName}
import zio.schema.{Schema, derived}

/**
 * Wire-format types for TypeSafe AI's `POST /v1/systemone` endpoint
 * (docs.typesafe.ai/api).
 *
 * The response is fully fixed-shape — every field of [[Answer]] /
 * [[Usage]] / [[SystemOneResponse]] is a concrete, known-in-advance
 * type — so those stay ordinary `derives Schema` case classes, decoded
 * by `zio-schema-json` exactly like `zio-bedrock-converse`'s wire types.
 *
 * The request is different: `state`, every question's `instructions`,
 * and criteria descriptions are described by whatever `Schema`-having
 * type each caller passed, which varies per call and per question
 * within one call. [[SystemOneRequest]] is therefore not `derives
 * Schema` at all — `Helpers` assembles it as a `zio.json.ast.Json` tree
 * (each dynamic piece encoded independently via `Codecs.toJsonAst` and
 * composed with `Json.Obj` / `Json.Arr`), and `Http` renders it with
 * `zio-json`'s own printer.
 */
private[zio_typesafe_ai] object Wire:

  /** The fully-assembled request body. Not `derives Schema` — see above. */
  case class SystemOneRequest(body: Json)

  /** Jev's answer to one question. Internally tagged on `"type"` so the
    * wire shape is flat: `{"type": "noul", "noul": 0.93}`. */
  @discriminatorName("type")
  enum Answer derives Schema:
    @caseName("noul")   case Noul(noul: Double)
    @caseName("choice") case Choice(choice: String, probabilities: Map[String, Double], confidence: Double)
    @caseName("score")  case Score(score: Double, legend: Map[String, String], probabilities: Map[String, Double], confidence: Double)

  case class Usage(
    @fieldName("input_tokens")  inputTokens:  Int,
    @fieldName("output_tokens") outputTokens: Int,
  ) derives Schema

  case class SystemOneResponse(
    model:   String,
    answers: Map[String, Answer],
    usage:   Usage,
  ) derives Schema
