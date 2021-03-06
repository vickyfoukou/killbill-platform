/*
 * Copyright 2014-2018 Groupon, Inc
 * Copyright 2014-2018 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.server.healthchecks;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.killbill.billing.osgi.api.Healthcheck;
import org.killbill.billing.osgi.api.Healthcheck.HealthStatus;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.health.HealthCheck;

@Singleton
public class KillbillPluginsHealthcheck extends HealthCheck {

    private static final Logger logger = LoggerFactory.getLogger(KillbillPluginsHealthcheck.class);

    private final OSGIServiceRegistration<Healthcheck> pluginHealthchecks;

    @Inject
    public KillbillPluginsHealthcheck(final OSGIServiceRegistration<Healthcheck> pluginHealthchecks) {
        this.pluginHealthchecks = pluginHealthchecks;
    }

    @Override
    public Result check() {
        final ResultBuilder resultBuilder = Result.builder();

        boolean isHealthy = true;
        for (final String pluginHealthcheckService : pluginHealthchecks.getAllServices()) {
            final Healthcheck pluginHealthcheck = pluginHealthchecks.getServiceForName(pluginHealthcheckService);
            if (pluginHealthcheck == null) {
                continue;
            }
            final HealthStatus pluginStatus = pluginHealthcheck.getHealthStatus(null, null);
            resultBuilder.withDetail(pluginHealthcheckService, pluginStatus.getDetails());
            isHealthy = isHealthy && pluginStatus.isHealthy();
        }

        return isHealthy ? resultBuilder.healthy().build() : resultBuilder.unhealthy().build();
    }
}
