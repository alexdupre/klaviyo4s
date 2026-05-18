package com.alexdupre.klaviyo.codegen.spec

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Duration

/** Downloads the Klaviyo combined OpenAPI spec (`stable.json`) from
  * the public github repository.
  *
  * The git ref is configurable. Klaviyo publishes its revisions as
  * date-named branches (e.g. `2026-04-15`), so pinning to a specific
  * revision is as simple as passing that ref. The default `main`
  * tracks the latest published spec.
  *
  * No retries — the caller (sbt task) can re-invoke on failure, and
  * sbt is the right layer to surface a failed download anyway.
  */
object SpecDownloader {

  /** Default upstream URL template. The `%s` is substituted with the
    * git ref.
    */
  val UrlTemplate: String =
    "https://raw.githubusercontent.com/klaviyo/openapi/%s/openapi/stable.json"

  /** Default git ref (Klaviyo's primary branch). */
  val DefaultRef: String = "main"

  /** Download the spec at `ref` and write it to `dest`.
    *
    * Creates parent directories as needed. Overwrites `dest` if it
    * already exists (`StandardCopyOption.REPLACE_EXISTING`).
    *
    * @return the path that was written (same as `dest`, normalised)
    */
  def download(ref: String = DefaultRef, dest: Path): Path = {
    val url = UrlTemplate.format(ref)
    val client = HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(30))
      .build()

    val req = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofMinutes(2))
      .header("Accept", "application/json")
      .GET()
      .build()

    Option(dest.getParent).foreach(Files.createDirectories(_))

    // Stream straight to a sibling temp file in the same directory,
    // then atomic-move into place. Avoids leaving a half-written
    // spec.json if the connection drops mid-transfer.
    val parent = Option(dest.getParent).getOrElse(Path.of("."))
    val tmp = Files.createTempFile(parent, ".klaviyo-spec-", ".json.tmp")
    try {
      val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp))
      if (resp.statusCode() != 200) {
        throw new RuntimeException(
          s"Failed to fetch $url: HTTP ${resp.statusCode()}"
        )
      }
      Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      dest.toAbsolutePath.normalize()
    } catch {
      case t: Throwable =>
        Files.deleteIfExists(tmp)
        throw t
    }
  }
}
