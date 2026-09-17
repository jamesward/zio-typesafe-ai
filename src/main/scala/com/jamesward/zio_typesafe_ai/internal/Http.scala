package com.jamesward.zio_typesafe_ai.internal

import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.{ApiKey, Error, ModelId}
import zio.*
import zio.http.*
import zio.json.*
import zio.schema.codec.JsonCodec

import java.nio.charset.StandardCharsets.UTF_8

/**
 * HTTP-level transport. Builds a `TypeSafeAI.Client` whose `send` is
 * rooted at `https://api.typesafe.ai` and authed with the bearer token.
 */
private[zio_typesafe_ai] object Http:

  private val baseUrl = "https://api.typesafe.ai"
  private val path    = "/v1/systemone"

  /** Build an authed `TypeSafeAI.Client` whose `send` makes real HTTP
    * calls. */
  def buildClient(apiKey: ApiKey, modelId: ModelId, client: Client): TypeSafeAI.Client =
    val base = URL.decode(baseUrl).toOption.get
    val authedClient = client
      .url(base)
      .addHeader(Header.Authorization.Bearer(apiKey.unwrap))
      .addHeader(Header.ContentType(MediaType.application.json))
    new HttpClient(modelId, authedClient)

  /** Live HTTP-backed `TypeSafeAI.Client`. Lives here (in `internal`) so
    * it has access to `Wire.*` and `TypeSafeAI.Client#send`'s
    * package-private surface. */
  private final class HttpClient(
    val modelId:    ModelId,
    private val hc: Client,
  ) extends TypeSafeAI.Client:

    private val respCodec = JsonCodec.schemaBasedBinaryCodec[Wire.SystemOneResponse](Codecs.codecConfig)

    def send(req: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse] =
      // `req.body` is already a fully-assembled `zio.json.ast.Json` tree
      // (see `Helpers.toWireRequest`) — rendering it is `zio-json`'s own
      // printer, not a derived `Schema`-based encoder, since the request
      // was never a `derives Schema` value in the first place.
      val body = Body.fromChunk(Chunk.fromArray(req.body.toJson.getBytes(UTF_8)))
      ZIO.scoped:
        hc.post(path)(body)
          .mapError(Error.Transport.apply)
          .flatMap: response =>
            val status = response.status
            if status.isSuccess then
              response.body.asChunk
                .mapError(Error.Transport.apply)
                .flatMap: bytes =>
                  ZIO.fromEither(respCodec.decode(bytes))
                    .mapError(de => Error.Unexpected(status, s"Decode failed: ${de.message}"))
            else
              response.body.asString.orDie.flatMap: text =>
                ZIO.fail(Error.fromStatus(status, text))
