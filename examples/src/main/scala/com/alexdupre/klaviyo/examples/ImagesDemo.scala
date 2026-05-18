package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

import java.nio.file.{Files, Path}
import java.util.Base64

/** Images category — list/get + optional upload-and-hide.
  *
  * Klaviyo's image API has no delete endpoint. Once an image is in
  * the account's asset library it stays there. The closest we can
  * get to "cleanup" is `updateImage(hidden = true)`, which removes
  * the asset from the picker UI but keeps the underlying file.
  *
  * By default we only list + fetch one image. Pass `--side-effects`
  * to additionally upload a public test image and immediately mark
  * it hidden so it doesn't clutter the asset library.
  */
object ImagesDemo extends DemoApp {

  def category: String = "images"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    section("list images (first page)") {
      val resp = client.images.getImages()
      log(s"got ${resp.data.size} image(s)")
      resp.data.take(3).foreach { i =>
        log(s"  id=${i.id} name=${i.attributes.name}")
      }
    }

    section("fetch first image by id (if any)") {
      val resp = client.images.getImages()
      resp.data.headOption match {
        case None => log("account has no images; skipping")
        case Some(i) =>
          val one = client.images.getImage(i.id)
          log(s"image id=${one.data.id} hidden=${one.data.attributes.hidden}")
      }
    }

    if (!sideEffectsEnabled) {
      log("(set --side-effects to upload a test image and mark it hidden; no delete endpoint exists)")
      return
    }

    section("upload image from URL, then mark hidden (side-effects)") {
      val resp = client.images.uploadImageFromUrl(
        ImageCreateQuery(
          data = ImageCreateQueryResourceObject(
            attributes = ImageCreateQueryResourceObject.Attributes(
              name = id("image"),
              importFromUrl = "https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png",
              hidden = true // hide immediately on creation
            )
          )
        )
      )
      val imgId = resp.data.id
      log(s"uploaded image id=$imgId (hidden)")

      // Belt-and-suspenders: even if `hidden=true` on create didn't
      // stick, force it now via PATCH. We don't register an undo
      // because there's no delete; we accept the asset will remain
      // (hidden) in the library.
      client.images.updateImage(
        id = imgId,
        body = ImagePartialUpdateQuery(
          data = ImagePartialUpdateQueryResourceObject(
            id = imgId,
            attributes = ImagePartialUpdateQueryResourceObject.Attributes(hidden = true)
          )
        )
      )
      log("hidden flag forced via updateImage")
    }

    section("upload image from local file (multipart), then mark hidden (side-effects)") {
      // Write a 1×1 transparent PNG to a temp file so the demo is
      // self-contained and doesn't depend on any image on disk. PNG
      // is one of the formats Klaviyo's `upload_image_from_file`
      // endpoint accepts (others: jpeg, gif). 67 bytes total.
      val tinyPng: Array[Byte] = Base64.getDecoder.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+P+/HgAFhAINDQ20tAAAAABJRU5ErkJggg=="
      )
      val tempFile: Path = Files.createTempFile("klaviyo4s-demo-", ".png")
      try {
        Files.write(tempFile, tinyPng)
        log(s"wrote ${tinyPng.length}-byte PNG to $tempFile")

        // `uploadImageFromFile` uses `multipart/form-data`. The codec
        // emits one `multipartFile` part for `file` (the binary
        // payload) and one `multipart` string part for each other
        // field that's present.
        val resp = client.images.uploadImageFromFile(
          ImageUploadQuery(
            file = tempFile,
            name = id("image-from-file"),
            hidden = true // hide immediately
          )
        )
        val imgId = resp.data.id
        log(s"uploaded image id=$imgId via multipart (hidden)")

        // Same belt-and-suspenders as the URL upload — no delete
        // endpoint exists so we force-hide and accept the asset
        // remains in the (hidden) library.
        client.images.updateImage(
          id = imgId,
          body = ImagePartialUpdateQuery(
            data = ImagePartialUpdateQueryResourceObject(
              id = imgId,
              attributes = ImagePartialUpdateQueryResourceObject.Attributes(hidden = true)
            )
          )
        )
        log("hidden flag forced via updateImage")
      } finally {
        Files.deleteIfExists(tempFile)
      }
    }
  }
}
