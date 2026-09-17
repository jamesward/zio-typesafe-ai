package com.jamesward.zio_typesafe_ai

import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import zio.*
import zio.http.Client
import zio.test.*
import zio.test.TestAspect.*

/**
 * The happy-path scenarios run against the live Jev service. Gated by
 * `TYPESAFE_AI_KEY`; skipped without it.
 *
 * Note the env var name here — `TYPESAFE_AI_KEY` — is *not* the
 * `TYPESAFE_API_KEY` the official SDKs default to; it's simply what
 * happens to be provisioned in this environment. `TypeSafeAI.Client.live`
 * still reads the SDK-standard `TYPESAFE_API_KEY`, so this spec builds
 * its layer explicitly via `Client.layer` instead of relying on `.live`.
 */
object TypeSafeAIIntegrationSpec extends ZIOSpecDefault:

  private def envOr(name: String, default: String): String =
    Option(java.lang.System.getenv(name)).getOrElse(default)

  private val testModelId: ModelId = ModelId(envOr("TYPESAFE_TEST_MODEL", ModelId.JevLatest.unwrap))
  private val testApiKey:  ApiKey  = ApiKey(envOr("TYPESAFE_AI_KEY", ""))

  private val testLayer: ZLayer[Client, Nothing, TypeSafeAI.Client] =
    TypeSafeAI.Client.layer(testApiKey, testModelId)

  private def asTest(s: SharedSpec.TypeSafeAIScenario): Spec[TypeSafeAI.Client, Any] =
    test(s.name)(s.run)

  def spec = suite("TypeSafeAI Integration")(
    SharedSpec.happyPathScenarios.map(asTest)*
  ).provideSomeShared[Scope](Client.default, testLayer)
    @@ ifEnvSet("TYPESAFE_AI_KEY")
    @@ withLiveClock
    @@ withLiveSystem
    @@ timeout(60.seconds)
    @@ sequential
