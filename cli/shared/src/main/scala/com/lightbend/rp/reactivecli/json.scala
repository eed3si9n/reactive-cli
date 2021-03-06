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

package com.lightbend.rp.reactivecli.json

import _root_.argonaut._
import scala.concurrent.Future
import com.lightbend.rp.reactivecli.process.jq

final case class JsonTransformExpression(value: String) extends AnyVal

sealed trait JsonTransform {
  def jsonTransform(json: Json): Future[Json]
}

object JsonTransform {
  def noop: JsonTransform = NoJsonTransform
  def jq(expr: JsonTransformExpression): JsonTransform = new jqJsonTransform(expr)
}

case object NoJsonTransform extends JsonTransform {
  def jsonTransform(json: Json) = Future.successful(json)
}

final class jqJsonTransform(expr: JsonTransformExpression) extends JsonTransform {
  def jsonTransform(json: Json) = jq.jsonTransform(json, expr)
}
