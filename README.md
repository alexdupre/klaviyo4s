# klaviyo4s

Scala 3 library / sbt plugin for the [Klaviyo](https://www.klaviyo.com/) REST API.

A typed client is generated **at build time** in your project, from the
official Klaviyo OpenAPI spec at
[`klaviyo/openapi`](https://github.com/klaviyo/openapi/blob/main/openapi/stable.json).
The sbt plugin handles fetching the spec, codegen, scalafmt, and caching;
your project just gets typed Scala sources under `src_managed/`.

- **Transport-agnostic** — built on [sttp-client 4](https://sttp.softwaremill.com).
  Any sttp backend works: `Identity` (synchronous), `Future`, cats-effect `IO`,
  ZIO, pekko-http, etc. The same `KlaviyoClient[F]` adapts to your chosen
  effect type automatically via the backend.
- **JSON** via [jsoniter-scala](https://github.com/plokhotnyuk/jsoniter-scala).
- **Errors as exceptions** — generated methods return `F[A]`; failures surface
  through your effect's natural error channel (`throw`, `Future.failed`,
  `IO.raiseError`, …) as subtypes of `KlaviyoError`.
- **Three-state fields** — every optional field is typed `Tristate.Optional[A]`,
  `Tristate.Nullable[A]`, or `Tristate.Maybe[A]`, so the distinction between
  *value*, *explicit `null`* and *omitted* is preserved end-to-end. Passing a
  plain value works thanks to an implicit `Conversion`.
- **JSON:API discriminators hidden** — every `type` field on a resource is
  injected by the codec, not the public case class. No more
  `type = AccountEnum.Account` boilerplate at every call site.
- **Nested types** — single-parent `Attributes` / `Relationships` / inline
  enums live under the parent's companion (`Foo.Attributes`,
  `Foo.Status`) instead of as standalone flat-named types.
- **Typed formats** — `date` / `date-time` / `uri` / `binary` map to
  `java.time.LocalDate` / `java.time.OffsetDateTime` / `java.net.URI` /
  `java.nio.file.Path`.
- **Multipart uploads** — `upload_image_from_file` uses sttp's multipart
  parts; binary fields take a `Path`.
- **Arbitrary-object fields** — JSON:API "open object" fields
  (`template render context`, `profile.properties`, `event.event_properties`,
  webhook headers, …) are typed as `RawJson` — validating-by-default,
  unchecked-passthrough opt-in. No empty case classes silently dropping data.
- **Smart retries** — `429` and `503` always retried; `500`/`502`/`504` and
  transport exceptions retried only for idempotent methods (`GET` / `HEAD` /
  `OPTIONS` / `TRACE` / `PUT` / `DELETE`). Pre-send transport errors
  (`ConnectException`, DNS, TLS handshake) retried regardless of method. Honours
  `Retry-After` exactly; fails fast when the server asks for longer than
  `RetryPolicy.maxDelay`.
- **Pagination helpers** — `collectAll` / `foreachPage` walk Klaviyo's
  cursor-based `links.next` for you.
- **Typed filters** — per-endpoint `<OperationName>Filter` builders generated
  from the `x-klaviyo-filters` spec extension; no hand-rolled
  `equals(id,"x")` strings. Filter values are URL-encoded, double-quoted for
  strings, bare for booleans / numbers / `OffsetDateTime`.
- **API slicing** — generate only the operations / categories you use via
  `klaviyoTags` and/or `klaviyoOperationIds`. Reachable DTOs are pruned
  automatically. Useful for projects that touch one slice of Klaviyo's
  surface (e.g. just `Profiles` + `Events`).

## Quick start

Add the plugin to `project/plugins.sbt`:

```scala
addSbtPlugin("com.alexdupre" % "sbt-klaviyo4s" % "x.y.z")
```

Enable it on your project in `build.sbt`:

```scala
lazy val app = (project in file("."))
  .enablePlugins(Klaviyo4sPlugin)
  .settings(
    scalaVersion := "3.4.2"
  )
```

Run `sbt compile`. The plugin downloads the spec to `klaviyo-specs/stable.json`,
generates the typed client under `src_managed/`, and your code compiles against
it. Commit `klaviyo-specs/stable.json` so other developers and CI get a
deterministic build.

## Using the client

```scala
import com.alexdupre.klaviyo.*           // KlaviyoClient, GeneratedSpecRevision, extensions
import com.alexdupre.klaviyo.models.*    // generated DTOs and Filter builders
import sttp.client4.httpurlconnection.HttpURLConnectionBackend

val backend = HttpURLConnectionBackend()
val config  = KlaviyoConfig(
  auth     = KlaviyoAuth.PrivateKey(sys.env("KLAVIYO_KEY")),
  revision = GeneratedSpecRevision
)
val client  = KlaviyoClient(backend, config)

val accounts = client.accounts.getAccounts()
val campaign = client.campaigns.getCampaigns(
  filter = GetCampaignsFilter.archived.equals(false)
)
```

`GeneratedSpecRevision` is a constant the codegen emits at the package root.
It always matches the spec your project regenerated against, so the runtime
`revision` header is guaranteed to track the shapes your generated DTOs encode.

### `Tristate` cheat sheet

| Spec field shape       | Generated type        | Reachable cases                |
| ---------------------- | --------------------- | ------------------------------ |
| required, non-nullable | `T`                   | (just the value)               |
| required, nullable     | `Tristate.Nullable[T]`| `Null \| Value`                |
| optional, non-nullable | `Tristate.Optional[T]`| `Absent \| Value`              |
| optional, nullable     | `Tristate.Maybe[T]`   | `Absent \| Null \| Value`      |

The companion adds `Option` lifts (`toOptional`, `toNullable`, `toMaybeAbsent`,
`toMaybeNull`) and Option-flavoured eliminators (`toOption`, `get`,
`getOrElse`, `contains`, `exists`, `foreach`, `fold`, `map`).

## Plugin settings

| Setting | Type | Default | Purpose |
| --- | --- | --- | --- |
| `klaviyoSpecVersion`   | `String`      | `"main"`                  | Git ref of `klaviyo/openapi` to fetch. Use a date branch (e.g. `"2026-04-15"`) to pin a Klaviyo revision. |
| `klaviyoSpecsDir`      | `File`        | `<project>/klaviyo-specs` | Where the downloaded spec is cached. Commit `stable.json` to make builds reproducible. |
| `klaviyoBasePackage`   | `String`      | `"com.alexdupre.klaviyo"` | Root Scala package for generated sources. Override to namespace under your org. |
| `klaviyoUseScalafmt`   | `Boolean`     | `true`                    | Run scalafmt over emitted sources. Disable for offline / hermetic builds. |
| `klaviyoTags`          | `Seq[String]` | `Seq.empty`               | Restrict generation to listed operation tags (e.g. `Seq("Profiles", "Lists")`). Empty = no filter. Composes additively with `klaviyoOperationIds`. |
| `klaviyoOperationIds`  | `Seq[String]` | `Seq.empty`               | Restrict generation to listed `operationId`s (e.g. `Seq("get_lists", "create_event")`). Composes with `klaviyoTags` as a union — useful for pulling a single extra op from a different category. |
| `klaviyoRefreshSpecs`  | task          | —                         | Re-download the spec at `klaviyoSpecVersion`. Invoked automatically on first build. |
| `klaviyoGenerate`      | task          | —                         | Regenerate sources. Wired into `Compile / sourceGenerators`; cached on spec hash + plugin version + settings. |

## Runtime configuration

`KlaviyoConfig` exposes the runtime knobs:

| Field | Type | Default | Purpose |
| --- | --- | --- | --- |
| `auth`         | `KlaviyoAuth`   | required           | Currently `KlaviyoAuth.PrivateKey`; future OAuth-bearer adds a new case. |
| `revision`     | `String`        | required           | Pass `GeneratedSpecRevision` so the `revision` header tracks the generated DTOs. |
| `baseUri`      | `String`        | `https://a.klaviyo.com` | Override for stubs / regional endpoints. |
| `retry`        | `RetryPolicy`   | `RetryPolicy.default`   | Per-request retry behaviour (see below). |
| `userAgent`    | `String`        | `klaviyo4s/<BuildInfo.version>` | Tag your application on top of this for Klaviyo's traffic correlation. |
| `readTimeout`  | `FiniteDuration`| `30.seconds`       | Per-attempt response wait. Surfaces as `KlaviyoError.Transport` on idempotent methods (retried per `RetryPolicy`) or fails fast on writes. Connect timeout is configured on the sttp backend itself. |

`RetryPolicy` defaults: 5 attempts, 200ms base, 30s cap, full-jitter exp backoff.
Set `retryTransient = false` to collapse to the conservative
"429 + 503 only" behaviour.

## Sleep typeclass

Retries call `Sleep[F].sleep(duration)`. Two givens ship with the library:

- `Sleep.identitySleep` for `F = Identity` — uses `Thread.sleep`.
- `Sleep.futureSleep` for `F = Future` — non-blocking, schedules on a shared
  `klaviyo4s-scheduler` daemon thread.

Users on cats-effect / ZIO / pekko-http typically write their own given that
delegates to `IO.sleep` / `ZIO.sleep` / `system.scheduler.scheduleOnce`. The
typeclass is the only extension point — define `given Sleep[F]` in scope and
your instance shadows the default.

## Modules

| Module | Scala | Published | Purpose |
| ------ | ----- | --------- | ------- |
| `klaviyo4s-core`     | 3     | yes | Runtime: `KlaviyoClient`, JSON:API envelope, errors, `Tristate`, `RawJson`, retry, pagination. |
| `klaviyo4s-codegen`  | 2.12  | yes | Build-time library: OpenAPI parser + scala.meta emitter + spec downloader. |
| `sbt-klaviyo4s`      | 2.12  | yes | sbt 1.x plugin wrapping the codegen. |

`klaviyo4s-codegen` and the plugin run on Scala 2.12 because sbt 1.x is on
Scala 2.12. The codegen *emits* Scala 3 sources — there's no Scala 3 → 2.12
coupling at runtime.

## Maintainer flow

```
sbt codegen/runMain com.alexdupre.klaviyo.codegen.Generate --fetch-if-missing
sbt sbtPlugin212/scripted   # plugin scripted tests including a full scalafmt path
sbt test                    # core + codegen + examples
sbt sbtPlugin212/publishLocal && sbt codegen/publishLocal && sbt core/publishLocal
```

## License

[BSD 2-Clause](./LICENSE). © 2026 Alex Dupre.
