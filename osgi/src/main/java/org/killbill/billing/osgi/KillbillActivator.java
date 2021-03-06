/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2019 Groupon, Inc
 * Copyright 2014-2019 The Billing Project, LLC
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

package org.killbill.billing.osgi;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Named;
import javax.servlet.Servlet;
import javax.sql.DataSource;

import org.killbill.billing.catalog.plugin.api.CatalogPluginApi;
import org.killbill.billing.control.plugin.api.PaymentControlPluginApi;
import org.killbill.billing.currency.plugin.api.CurrencyPluginApi;
import org.killbill.billing.entitlement.plugin.api.EntitlementPluginApi;
import org.killbill.billing.invoice.plugin.api.InvoicePluginApi;
import org.killbill.billing.osgi.api.Healthcheck;
import org.killbill.billing.osgi.api.OSGIConfigProperties;
import org.killbill.billing.osgi.api.OSGIKillbill;
import org.killbill.billing.osgi.api.OSGIKillbillRegistrar;
import org.killbill.billing.osgi.api.OSGIPluginProperties;
import org.killbill.billing.osgi.api.OSGIServiceDescriptor;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.osgi.glue.DefaultOSGIModule;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.platform.jndi.JNDIManager;
import org.killbill.billing.usage.plugin.api.UsagePluginApi;
import org.killbill.clock.Clock;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.Event;
import org.osgi.service.http.HttpService;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.google.inject.Inject;

public class KillbillActivator implements BundleActivator, ServiceListener {

    static final int PLUGIN_NAME_MAX_LENGTH = 40;
    static final Pattern PLUGIN_NAME_PATTERN = Pattern.compile("\\p{Lower}(?:\\p{Lower}|\\d|-|_)*");

    private static final Logger logger = LoggerFactory.getLogger(KillbillActivator.class);
    private static final String KILLBILL_OSGI_JDBC_JNDI_NAME = "killbill/osgi/jdbc";

    private static final String LOG_SERVICE_NAME = "org.osgi.service.log.LogService";

    private final OSGIKillbill osgiKillbill;
    private final HttpService defaultHttpService;
    private final DataSource dataSource;
    private final Clock clock;
    private final KillbillEventRetriableBusHandler killbillEventRetriableBusHandler;
    private final KillbillEventObservable observable;
    private final OSGIKillbillRegistrar registrar;
    private final OSGIConfigProperties configProperties;
    private final JNDIManager jndiManager;
    private final MetricRegistry metricsRegistry;
    private final BundleRegistry bundleRegistry;
    private final List<OSGIServiceRegistration> allRegistrationHandlers;

    private BundleContext context = null;
    private ServiceTracker<LogService, LogService> logTracker;
    private OSGIAppender osgiAppender = null;

    @Inject
    public KillbillActivator(@Named(DefaultOSGIModule.OSGI_DATA_SOURCE_ID) final DataSource dataSource,
                             final OSGIKillbill osgiKillbill,
                             final Clock clock,
                             final BundleRegistry bundleRegistry,
                             final HttpService defaultHttpService,
                             final KillbillEventRetriableBusHandler killbillEventRetriableBusHandler,
                             final KillbillEventObservable observable,
                             final OSGIConfigProperties configProperties,
                             final MetricRegistry metricsRegistry,
                             final JNDIManager jndiManager) {
        this.osgiKillbill = osgiKillbill;
        this.bundleRegistry = bundleRegistry;
        this.defaultHttpService = defaultHttpService;
        this.dataSource = dataSource;
        this.clock = clock;
        this.killbillEventRetriableBusHandler = killbillEventRetriableBusHandler;
        this.observable = observable;
        this.configProperties = configProperties;
        this.jndiManager = jndiManager;
        this.metricsRegistry = metricsRegistry;
        this.registrar = new OSGIKillbillRegistrar();
        this.allRegistrationHandlers = new LinkedList<OSGIServiceRegistration>();
    }

    @Inject(optional = true)
    public void addServletOSGIServiceRegistration(final OSGIServiceRegistration<Servlet> servletRouter) {
        allRegistrationHandlers.add(servletRouter);
    }

    @Inject(optional = true)
    public void addPaymentPluginApiOSGIServiceRegistration(final OSGIServiceRegistration<PaymentPluginApi> paymentProviderPluginRegistry) {
        allRegistrationHandlers.add(paymentProviderPluginRegistry);
    }

    @Inject(optional = true)
    public void addInvoicePluginApiOSGIServiceRegistration(final OSGIServiceRegistration<InvoicePluginApi> invoiceProviderPluginRegistry) {
        allRegistrationHandlers.add(invoiceProviderPluginRegistry);
    }

    @Inject(optional = true)
    public void addCurrencyPluginApiOSGIServiceRegistration(final OSGIServiceRegistration<CurrencyPluginApi> currencyProviderPluginRegistry) {
        allRegistrationHandlers.add(currencyProviderPluginRegistry);
    }

    @Inject(optional = true)
    public void addPaymentControlPluginApiOSGIServiceRegistration(final OSGIServiceRegistration<PaymentControlPluginApi> paymentControlProviderPluginRegistry) {
        allRegistrationHandlers.add(paymentControlProviderPluginRegistry);
    }

    @Inject(optional = true)
    public void addCatalogPluginApiOSGIServiceRegistration(final OSGIServiceRegistration<CatalogPluginApi> catalogProviderPluginRegistry) {
        allRegistrationHandlers.add(catalogProviderPluginRegistry);
    }

    @Inject(optional = true)
    public void addEntitlementPluginApiOSGIServiceRegistration(final OSGIServiceRegistration<EntitlementPluginApi> entitlementProviderPluginRegistry) {
        allRegistrationHandlers.add(entitlementProviderPluginRegistry);
    }

    @Inject(optional = true)
    public void addUsagePluginApiOSGIServiceRegistration(final OSGIServiceRegistration<UsagePluginApi> usageProviderPluginRegistry) {
        allRegistrationHandlers.add(usageProviderPluginRegistry);
    }

    @Inject(optional = true)
    public void addHealthcheckOSGIServiceRegistration(final OSGIServiceRegistration<Healthcheck> healthcheckRegistry) {
        allRegistrationHandlers.add(healthcheckRegistry);
    }

    @Override
    public void start(final BundleContext context) throws Exception {
        this.context = context;
        final Dictionary<String, String> props = new Hashtable<String, String>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, "killbill");

        // Forward all core log entries to the OSGI LogService, so that plugins have access to them
        final Object factory = LoggerFactory.getILoggerFactory();
        if ("ch.qos.logback.classic.LoggerContext".equals(factory.getClass().getName())) {
            logTracker = new ServiceTracker<LogService, LogService>(context, LOG_SERVICE_NAME, null);
            logTracker.open();

            final ch.qos.logback.classic.Logger root = ((ch.qos.logback.classic.LoggerContext) factory).getLogger(Logger.ROOT_LOGGER_NAME);

            osgiAppender = new OSGIAppender(logTracker, context.getBundle());
            osgiAppender.setContext(root.getLoggerContext());
            osgiAppender.start();
            root.addAppender(osgiAppender);
        }

        killbillEventRetriableBusHandler.register();

        registrar.registerService(context, OSGIKillbill.class, osgiKillbill, props);
        registrar.registerService(context, HttpService.class, defaultHttpService, props);
        registrar.registerService(context, Observable.class, observable, props);
        registrar.registerService(context, DataSource.class, dataSource, props);
        registrar.registerService(context, OSGIConfigProperties.class, configProperties, props);
        registrar.registerService(context, Clock.class, clock, props);

        context.addServiceListener(this);

        jndiManager.export(KILLBILL_OSGI_JDBC_JNDI_NAME, dataSource);
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        jndiManager.unExport(KILLBILL_OSGI_JDBC_JNDI_NAME);

        this.context = null;
        context.removeServiceListener(this);
        killbillEventRetriableBusHandler.unregister();
        registrar.unregisterAll();

        if (osgiAppender != null) {
            osgiAppender.stop();
        }
        if (logTracker != null) {
            logTracker.close();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void serviceChanged(final ServiceEvent event) {
        if (context == null || (event.getType() != ServiceEvent.REGISTERED && event.getType() != ServiceEvent.UNREGISTERING)) {
            // We are not initialized or uninterested
            return;
        }
        final ServiceReference<?> serviceReference = event.getServiceReference();
        for (final OSGIServiceRegistration cur : allRegistrationHandlers) {
            if (listenForServiceType(serviceReference, event.getType(), cur.getServiceType(), cur)) {
                break;
            }
        }
    }

    public void sendEvent(final String topic, final Map<String, String> properties) {
        observable.setChangedAndNotifyObservers(new Event(topic, properties));
    }

    public List<OSGIServiceRegistration> getAllRegistrationHandlers() {
        return allRegistrationHandlers;
    }

    private <T> boolean listenForServiceType(final ServiceReference<?> serviceReference, final int eventType, final Class<T> claz, final OSGIServiceRegistration<T> registration) {
        // Make sure we can retrieve the plugin name
        final String serviceName = (String) serviceReference.getProperty(OSGIPluginProperties.PLUGIN_NAME_PROP);
        if (serviceName == null || !checkSanityPluginRegistrationName(serviceName)) {
            // Quite common for non Killbill bundles
            logger.debug("Ignoring registered OSGI service {} with no {} property", claz.getName(), OSGIPluginProperties.PLUGIN_NAME_PROP);
            return true;
        }

        final Object theServiceObject = context.getService(serviceReference);
        // Is that for us? We look for a subclass here for greater flexibility (e.g. HttpServlet for a Servlet service)
        if (theServiceObject == null || !claz.isAssignableFrom(theServiceObject.getClass())) {
            return false;
        }
        @SuppressWarnings("unchecked") final T theService = (T) theServiceObject;

        final OSGIServiceDescriptor desc = new DefaultOSGIServiceDescriptor(serviceReference.getBundle().getSymbolicName(),
                                                                            bundleRegistry.getPluginName(serviceReference.getBundle()),
                                                                            serviceName);
        switch (eventType) {
            case ServiceEvent.REGISTERED:
                final T wrappedService = ContextClassLoaderHelper.getWrappedServiceWithCorrectContextClassLoader(theService, registration.getServiceType(), serviceName, metricsRegistry);
                registration.registerService(desc, wrappedService);
                bundleRegistry.registerService(desc, registration.getServiceType().getName());
                break;
            case ServiceEvent.UNREGISTERING:
                registration.unregisterService(desc.getRegistrationName());
                bundleRegistry.unregisterService(desc, registration.getServiceType().getName());
                break;
            default:
                break;
        }
        return true;
    }

    private boolean checkSanityPluginRegistrationName(final String pluginName) {
        final Matcher m = PLUGIN_NAME_PATTERN.matcher(pluginName);
        if (!m.matches()) {
            logger.warn("Invalid plugin name {} : should be of the form {}", pluginName, PLUGIN_NAME_PATTERN.toString());
            return false;
        }
        if (pluginName.length() > PLUGIN_NAME_MAX_LENGTH) {
            logger.warn("Invalid plugin name {} : too long, should be less than {}", pluginName, PLUGIN_NAME_MAX_LENGTH);
            return false;
        }
        return true;
    }
}
