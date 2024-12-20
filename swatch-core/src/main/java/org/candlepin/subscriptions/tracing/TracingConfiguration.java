/*
 * Copyright Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.tracing;

import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(value = {TracingService.class})
public class TracingConfiguration {

  /**
   * We don't want to import the "opentelemetry-exporter-otlp" dependency because we're exporting
   * the traces via the java agent. Therefore, we need to configure the ContextPropagators by our
   * own.
   */
  @Bean
  ContextPropagators contextPropagators() {
    return W3CTraceContextPropagator::getInstance;
  }

  @Bean
  OtelPropagator otelPropagator(ContextPropagators contextPropagators, Tracer tracer) {
    return new OtelPropagator(contextPropagators, tracer);
  }
}
