/*
 * Copyright 2017 Lightbend, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightbend.rp.reactivecli.http

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.scalanative.native
import scala.scalanative.native._
import scala.util.{ Failure, Success, Try }
import scala.collection.immutable.Seq

object Http {
  type HttpExchange = HttpRequest => Try[HttpResponse]

  private val CRLF = "\r\n"
  private val HttpHeaderAndBodyPartsSeparator = CRLF + CRLF
  private val HttpHeaderNameAndValueSeparator = ":"

  case class InternalNativeFailure(errorCode: Long, errorDescription: String) extends RuntimeException(s"$errorCode: $errorDescription")

  case class InfiniteRedirect(visited: List[String]) extends RuntimeException(s"Infinte redirect detected: $visited")

  /**
   * Common settings for [[Http]].
   *
   * @param followRedirect If true, follow redirect up to the number of hops specified by [[maxRedirects]].
   *                       Else, doesn't follow redirect, i.e. returns the response with `Location` header.
   * @param tlsValidationEnabled If true, instructs the underlying `libcurl` to perform TLS validation.
   *                             Else, `libcurl` will set `CURLOPT_SSL_VERIFYPEER` to `0`.
   * @param tlsCacertsPath Paths to CA certs used for TLS validation.
   *                       Optional. If specified, this will be supplied to `libcurl` via `CURLOPT_CAINFO` option.
   * @param maxRedirects The maximum number of redirects allowed when attempting HTTP request.
   *                     This setting is in place to prevent infinite redirect loop.
   */
  case class Settings(
    followRedirect: Boolean = true,
    tlsValidationEnabled: Boolean = true,
    tlsCacertsPath: Option[Path] = None,
    tlsCertPath: Option[Path] = None,
    tlsKeyPath: Option[Path] = None,
    maxRedirects: Int = Settings.DefaultMaxRedirects)

  object Settings {
    val DefaultMaxRedirects = 5
  }

  val defaultSettings = Settings()

  def http(implicit settings: Settings): HttpExchange = apply

  /**
   * Initializes libcurl` internal state by calling `curl_global_init` underneath.
   * This method is _NOT_ thread safe and it's meant to be called at the start of the program.
   */
  def globalInit(): Try[Unit] =
    native.Zone { implicit z =>
      val errorCode = nativebinding.http.global_init()
      if (errorCode.toInt == 0)
        Success(Unit)
      else
        Failure(InternalNativeFailure(-70, "Failure calling curl_global_init"))
    }

  /**
   * Performs cleanup of libcurl` internal state by calling `curl_global_cleanup` underneath.
   * This method is _NOT_ thread safe and it's meant to be called before termination of the program.
   */
  def globalCleanup(): Unit =
    native.Zone { implicit z =>
      nativebinding.http.global_cleanup()
    }

  def apply(request: HttpRequest)(implicit settings: Settings): Try[HttpResponse] =
    doHttp(
      request.requestMethod,
      request.requestUrl,
      request.requestHeaders.headers,
      request.auth,
      request.requestBody,
      request.requestFollowRedirects,
      request.tlsValidationEnabled,
      Nil)

  private def doHttp(
    method: String,
    url: String,
    headers: Map[String, String],
    auth: Option[HttpRequest.Auth],
    requestBody: Option[String],
    followRedirects: Option[Boolean],
    tlsValidationEnabled: Option[Boolean],
    visitedUrls: List[String])(implicit settings: Settings): Try[HttpResponse] =
    native.Zone { implicit z =>
      val isFollowRedirect = followRedirects.getOrElse(settings.followRedirect)
      val isTlsValidationEnabled = tlsValidationEnabled.getOrElse(settings.tlsValidationEnabled)

      val headersWithAuth = auth.foldLeft(headers) {
        case (hs, HttpRequest.BasicAuth(username, password)) =>
          hs.updated(
            "Authorization",
            s"Basic ${Base64Encoder(s"$username:$password")}")

        case (hs, HttpRequest.BearerToken(bearer)) =>
          hs.updated("Authorization", s"Bearer $bearer")
      }

      val response = nativebinding.http.do_http(
        validate_tls = if (isTlsValidationEnabled) 1 else 0,
        method,
        url,
        httpHeadersToDelimitedString(headersWithAuth),
        requestBody.getOrElse(""),
        settings.tlsCacertsPath.fold("")(_.toString),
        settings.tlsCertPath.fold("")(_.toString),
        settings.tlsKeyPath.fold("")(_.toString))

      val result =
        if (response.error == 0) {
          val hs = HttpHeaders(parseHeaders(response.header))

          if (isFollowRedirect && response.status >= 300 && response.status <= 399 && hs.contains("Location")) {
            val location = hs("Location")

            if (visitedUrls.contains(location) || visitedUrls.length >= settings.maxRedirects)
              Failure(InfiniteRedirect(visitedUrls))
            else
              doHttp("GET", location, Map.empty, auth, None, followRedirects, tlsValidationEnabled, location :: visitedUrls)
          } else {
            Success(HttpResponse(response.status, hs, response.body))
          }
        } else {
          val msg = nativebinding.http.error_message(response.error)
          Failure(InternalNativeFailure(response.error, msg))
        }

      result
    }

  private def httpHeadersToDelimitedString(headers: Map[String, String]): Seq[String] =
    headers
      .map {
        case (headerName, headerValue) => s"$headerName$HttpHeaderNameAndValueSeparator $headerValue"
      }.toVector

  private def parseHeaders(input: Option[String]): Map[String, String] = {
    def splitBySeparator(v: String, separator: String): (String, String) = {
      val lineBreakIndex = v.indexOf(separator)
      val (l, r) = v.splitAt(lineBreakIndex)
      l -> r.substring(separator.length)
    }

    input match {
      case Some(headers) =>
        // Exclude the first line which is the HTTP status line
        headers.split(CRLF).tail.foldLeft(Map.empty[String, String]) { (v, l) =>
          val (headerName, headerValue) = splitBySeparator(l, HttpHeaderNameAndValueSeparator)
          v.updated(headerName, headerValue.trim)
        }
      case _ =>
        Map.empty[String, String]
    }
  }
}