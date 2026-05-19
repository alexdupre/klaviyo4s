package com.alexdupre.klaviyo.examples

import com.alexdupre.klaviyo.*
import com.alexdupre.klaviyo.models.*

/** Coupons category — CRUD on coupons + a coupon code attached to a coupon.
  *
  * Coupon + coupon-code form a parent/child pair; deleting the parent
  * cleans up child codes server-side, but we still register both
  * cleanups so a failed delete of the child surfaces clearly.
  */
object CouponsDemo extends DemoApp {

  def category: String = "coupons"

  def runDemo(ctx: DemoCtx): Unit = {
    import ctx.*

    val couponExtId = id("coupon")
    var couponId: String = null
    var couponCodeId: String = null

    section("create coupon") {
      val resp = client.coupons.createCoupon(
        CouponCreateQuery(
          data = CouponCreateQueryResourceObject(
            attributes = CouponCreateQueryResourceObject.Attributes(
              externalId = couponExtId,
              description = "klaviyo4s demo coupon — safe to delete"
            )
          )
        )
      )
      couponId = resp.data.id
      register(s"deleteCoupon($couponId)") {
        client.coupons.deleteCoupon(couponId)
      }
      log(s"created coupon id=$couponId external_id=$couponExtId")
    }

    section("getCoupon by id") {
      val one = client.coupons.getCoupon(couponId)
      val desc = for {
        d <- one.data
        x <- d.attributes.description
      } yield x
      log(s"description=${desc.getOrElse("?")}")
    }

    section("create coupon code attached to the coupon") {
      val code = s"DEMO-$rand".toUpperCase
      val resp = client.coupons.createCouponCode(
        CouponCodeCreateQuery(
          data = CouponCodeCreateQueryResourceObject(
            attributes = CouponCodeCreateQueryResourceObject.Attributes(
              uniqueCode = code
            ),
            relationships = CouponCodeCreateQueryResourceObject.Relationships(
              coupon = CouponCodeCreateQueryResourceObject.Relationships.Coupon(
                data = CouponCodeCreateQueryResourceObject.Relationships.Coupon.Data(
                  id = couponId
                )
              )
            )
          )
        )
      )
      couponCodeId = resp.data.id
      register(s"deleteCouponCode($couponCodeId)") {
        client.coupons.deleteCouponCode(couponCodeId)
      }
      log(s"created coupon code id=$couponCodeId unique=$code")
    }

    section("list coupons (first page) and confirm visibility") {
      val all = client.coupons.getCoupons()
      log(s"page has ${all.data.size} coupon(s); created id present=${all.data.exists(_.id == couponId)}")
    }

    section("list codes for our coupon") {
      val codes = client.coupons.getCouponCodesForCoupon(couponId)
      log(s"coupon has ${codes.data.size} code(s)")
    }
  }
}
