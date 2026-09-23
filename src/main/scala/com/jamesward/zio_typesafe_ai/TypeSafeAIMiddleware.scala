package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import com.jamesward.zio_typesafe_ai.internal.{Codecs, Wire}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Composable middleware around every physical TypeSafe AI exchange.
  * Composition follows ZIO HTTP: `left ++ right` applies `left` around
  * `right`, and `@@` is an alias for `++`.
  */
trait TypeSafeAIMiddleware { self =>
  import TypeSafeAIMiddleware.*

  def apply(request: Request, next: IO[Error, Response]): IO[Error, Response]

  final def @@(that: TypeSafeAIMiddleware): TypeSafeAIMiddleware =
    self ++ that

  final def ++(that: TypeSafeAIMiddleware): TypeSafeAIMiddleware =
    TypeSafeAIMiddleware.make: (request, next) =>
      self(request, that(request, next))

  final def layer: URLayer[Client, Client] =
    ZLayer.fromFunction: (underlying: Client) =>
      new Client:
        val modelId: ModelId = underlying.modelId

        def send(request: Wire.SystemOneRequest): IO[Error, Wire.SystemOneResponse] =
          self(
            Request(request.body),
            underlying.send(request).map(Response(_)),
          ).map(_.wire)
}

object TypeSafeAIMiddleware:
  final case class Request(body: Json)

  final class Response private[zio_typesafe_ai] (
    private[zio_typesafe_ai] val wire: Wire.SystemOneResponse,
  ):
    lazy val body: Json = Codecs.toJsonAst(wire)

  private[zio_typesafe_ai] object Response:
    def apply(wire: Wire.SystemOneResponse): Response = new Response(wire)

  final case class LoggingConfig(
    logRequest: Boolean = true,
    logResponse: Boolean = true,
  )

  val identity: TypeSafeAIMiddleware = make((_, next) => next)

  def make(
    f: (Request, IO[Error, Response]) => IO[Error, Response]
  ): TypeSafeAIMiddleware =
    new TypeSafeAIMiddleware:
      def apply(request: Request, next: IO[Error, Response]): IO[Error, Response] =
        f(request, next)

  /** Runs an effect after each exchange without allowing callback defects or
    * self-interruption to change the client result.
    */
  def observe(
    f: (Request, Exit[Error, Response], Long) => UIO[Unit]
  ): TypeSafeAIMiddleware =
    make: (request, next) =>
      Clock.nanoTime.flatMap: started =>
        next.onExit: exit =>
          Clock.nanoTime.flatMap: finished =>
            TypeSafeAI.notifyBestEffort("TypeSafe AI middleware"):
              f(request, exit, (finished - started) / 1000000L)

  /** Logs complete request and canonical response bodies. Enable only where
    * logs are approved to contain application state and model output.
    */
  def logging(config: LoggingConfig = LoggingConfig()): TypeSafeAIMiddleware =
    observe: (request, exit, latencyMs) =>
      exit match
        case Exit.Success(response) =>
          val requestText = if config.logRequest then s"; request=${request.body.toJson}" else ""
          val responseText = if config.logResponse then s"; response=${response.body.toJson}" else ""
          ZIO.logInfo(s"TypeSafe AI exchange completed in ${latencyMs}ms$requestText$responseText")
        case Exit.Failure(cause) =>
          val requestText = if config.logRequest then s"; request=${request.body.toJson}" else ""
          ZIO.logErrorCause(s"TypeSafe AI exchange failed in ${latencyMs}ms$requestText", cause)

  private[zio_typesafe_ai] def fromObserver(observer: ExchangeObserver): TypeSafeAIMiddleware =
    observe: (request, exit, latencyMs) =>
      val outcome = exit match
        case Exit.Success(response) => ExchangeOutcome.Success(response.body)
        case Exit.Failure(cause)    => ExchangeOutcome.Failure(cause)
      observer.observe(ExchangeObservation(request.body, outcome, latencyMs))

  extension [R, E](layer: ZLayer[R, E, Client])
    def @@(middleware: TypeSafeAIMiddleware): ZLayer[R, E, Client] =
      layer >>> middleware.layer
