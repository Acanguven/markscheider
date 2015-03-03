/*
* Copyright 2013 Kenshoo.com
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.kenshoo.play.metrics

import play.api.mvc._
import play.api.http.Status
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.codahale.metrics._
import com.codahale.metrics.MetricRegistry.name

trait MetricsFilter extends EssentialFilter {

  def registry: MetricRegistry

  val knownStatuses = Seq(Status.OK, Status.BAD_REQUEST, Status.FORBIDDEN, Status.NOT_FOUND,
    Status.CREATED, Status.TEMPORARY_REDIRECT, Status.INTERNAL_SERVER_ERROR)

  def statusCodes: Map[Int, Meter] = knownStatuses.map(s => s -> registry.meter(name(classOf[MetricsFilter], s.toString))).toMap

  def requestsTimer: Timer = registry.timer(name(classOf[MetricsFilter], "requestTimer"))

  def activeRequests: Counter = registry.counter(name(classOf[MetricsFilter], "activeRequests"))

  def otherStatuses: Meter = registry.meter(name(classOf[MetricsFilter], "other"))

  def staticMetrics(next: EssentialAction) = new EssentialAction {
    def apply(rh: RequestHeader) = {
      val context = requestsTimer.time()

      def logCompleted(result: Result): Result = {
        activeRequests.dec()
        context.stop()
        statusCodes.getOrElse(result.header.status, otherStatuses).mark()
        result
      }

      activeRequests.inc()
      next(rh).map(logCompleted)
    }
  }

  def apply(next: EssentialAction) = new EssentialAction {

    def apply(rh: RequestHeader) = {

      val method = rh.tags.getOrElse(play.api.Routes.ROUTE_ACTION_METHOD, "MetricsFilter")
      val controller = rh.tags.getOrElse(play.api.Routes.ROUTE_CONTROLLER, "com.kenshoo.play.metrics")

      val context = registry.timer(s"latency.$controller.$method").time()

      def logCompleted(result: Result): Result = {
        activeRequests.dec()
        context.stop()
        registry.meter(s"status.$controller.$method.${result.header.status}").mark()
        result
      }

      activeRequests.inc()
      next(rh).map(logCompleted)
    }
  }
}

/**
 * use this filter when writing play java. bypasses the no ctor problem of scala object
 */
class JavaMetricsFilter extends MetricsFilter {
  override def registry: MetricRegistry = MetricsRegistry.default
}

object MetricsFilter extends MetricsFilter {
  override def registry = MetricsRegistry.default
}
