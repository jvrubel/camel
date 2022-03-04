/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.main;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import org.apache.camel.CamelContext;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.StartupListener;
import org.apache.camel.component.platform.http.PlatformHttpComponent;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpRouter;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpServer;
import org.apache.camel.component.platform.http.vertx.VertxPlatformHttpServerConfiguration;
import org.apache.camel.console.DevConsole;
import org.apache.camel.console.DevConsoleRegistry;
import org.apache.camel.health.HealthCheck;
import org.apache.camel.health.HealthCheckHelper;
import org.apache.camel.health.HealthCheckRegistry;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.SimpleEventNotifierSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * To setup vertx http server in the running Camel application
 */
public final class VertxHttpServer {

    static VertxPlatformHttpRouter router;
    static VertxPlatformHttpServer server;
    static PlatformHttpComponent phc;

    private static final Logger LOG = LoggerFactory.getLogger(VertxHttpServer.class);
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean CONSOLE = new AtomicBoolean();
    private static final AtomicBoolean HEALTH_CHECK = new AtomicBoolean();

    private VertxHttpServer() {
    }

    public static void registerServer(CamelContext camelContext) {
        if (REGISTERED.compareAndSet(false, true)) {
            doRegisterServer(camelContext, 8080);
        }
    }

    public static void registerServer(CamelContext camelContext, int port) {
        if (REGISTERED.compareAndSet(false, true)) {
            doRegisterServer(camelContext, port);
        }
    }

    private static void doRegisterServer(CamelContext camelContext, int port) {
        try {
            VertxPlatformHttpServerConfiguration config = new VertxPlatformHttpServerConfiguration();
            config.setPort(port);
            server = new VertxPlatformHttpServer(config);
            camelContext.addService(server);
            server.start();
            router = VertxPlatformHttpRouter.lookup(camelContext);
            if (phc == null) {
                phc = camelContext.getComponent("platform-http", PlatformHttpComponent.class);
            }

            // after camel is started then add event notifier
            camelContext.addStartupListener(new StartupListener() {
                @Override
                public void onCamelContextStarted(CamelContext context, boolean alreadyStarted) throws Exception {
                    camelContext.getManagementStrategy().addEventNotifier(new SimpleEventNotifierSupport() {

                        private Set<String> last;

                        @Override
                        public boolean isEnabled(CamelEvent event) {
                            return event instanceof CamelEvent.CamelContextStartedEvent
                                    || event instanceof CamelEvent.RouteReloadedEvent;
                        }

                        @Override
                        public void notify(CamelEvent event) throws Exception {
                            // when reloading then there may be more routes in the same batch, so we only want
                            // to log the summary at the end
                            if (event instanceof CamelEvent.RouteReloadedEvent) {
                                CamelEvent.RouteReloadedEvent re = (CamelEvent.RouteReloadedEvent) event;
                                if (re.getIndex() < re.getTotal()) {
                                    return;
                                }
                            }

                            Set<String> endpoints = phc.getHttpEndpoints();
                            if (endpoints.isEmpty()) {
                                return;
                            }

                            // log only if changed
                            if (last == null || last.size() != endpoints.size() || !last.containsAll(endpoints)) {
                                LOG.info("HTTP endpoints summary");
                                for (String u : endpoints) {
                                    LOG.info("    http://0.0.0.0:" + port + u);
                                }
                            }

                            // use a defensive copy of last known endpoints
                            last = new HashSet<>(endpoints);
                        }
                    });
                }
            });

        } catch (Exception e) {
            throw RuntimeCamelException.wrapRuntimeException(e);
        }
    }

    public static void registerConsole(CamelContext camelContext) {
        if (CONSOLE.compareAndSet(false, true)) {
            doRegisterConsole(camelContext);
        }
    }

    private static void doRegisterConsole(CamelContext context) {
        Route dev = router.route("/dev");
        dev.method(HttpMethod.GET);
        dev.produces("text/plain");
        dev.handler(new Handler<RoutingContext>() {
            @Override
            public void handle(RoutingContext ctx) {
                ctx.response().putHeader("content-type", "text/plain");

                DevConsoleRegistry dcr = context.getExtension(DevConsoleRegistry.class);
                if (dcr != null && dcr.isEnabled()) {
                    StringBuilder sb = new StringBuilder();
                    dcr.stream().forEach(c -> {
                        if (c.supportMediaType(DevConsole.MediaType.TEXT)) {
                            String text = (String) c.call(DevConsole.MediaType.TEXT);
                            if (text != null) {
                                sb.append(c.getDisplayName()).append(":");
                                sb.append("\n\n");
                                sb.append(text);
                                sb.append("\n\n");
                            }
                        }
                    });
                    if (sb.length() > 0) {
                        ctx.end(sb.toString());
                    } else {
                        ctx.end("Developer Console is not enabled");
                    }
                } else {
                    ctx.end("Developer Console is not enabled");
                }
            }
        });
        phc.addHttpEndpoint("/dev");
    }

    public static void registerHealthCheck(CamelContext camelContext) {
        if (HEALTH_CHECK.compareAndSet(false, true)) {
            doRegisterHealthCheck(camelContext);
        }
    }

    private static void doRegisterHealthCheck(CamelContext context) {
        final Route health = router.route("/health");
        health.method(HttpMethod.GET);
        health.produces("application/json");
        final Route live = router.route("/health/live");
        live.method(HttpMethod.GET);
        live.produces("application/json");
        final Route ready = router.route("/health/ready");
        ready.method(HttpMethod.GET);
        ready.produces("application/json");

        Handler<RoutingContext> handler = new Handler<RoutingContext>() {
            @Override
            public void handle(RoutingContext ctx) {
                ctx.response().putHeader("content-type", "application/json");

                boolean all = ctx.currentRoute() == health;
                boolean liv = ctx.currentRoute() == live;

                Collection<HealthCheck.Result> res;
                if (all) {
                    res = HealthCheckHelper.invoke(context);
                } else if (liv) {
                    res = HealthCheckHelper.invokeLiveness(context);
                } else {
                    res = HealthCheckHelper.invokeReadiness(context);
                }

                StringBuilder sb = new StringBuilder();
                sb.append("{\n");

                HealthCheckRegistry registry = HealthCheckRegistry.get(context);
                String level = registry.getExposureLevel();
                if ("oneline".equals(level)) {
                    healthCheckOneline(sb, res);
                } else if ("full".equals(level)) {
                    healthCheckFull(sb, res);
                } else {
                    healthCheckDefault(sb, res);
                }
                sb.append("}\n");
                ctx.end(sb.toString());
            }
        };
        health.handler(handler);
        live.handler(handler);
        ready.handler(handler);

        phc.addHttpEndpoint("/health");
    }

    private static void healthCheckOneline(StringBuilder sb, Collection<HealthCheck.Result> res) {
        boolean up = res.stream().noneMatch(r -> r.getState().equals(HealthCheck.State.DOWN));
        if (up) {
            sb.append("    \"status\": \"UP\"\n");
        } else {
            sb.append("    \"status\": \"DOWN\"\n");
        }
    }

    private static void healthCheckFull(StringBuilder sb, Collection<HealthCheck.Result> res) {
        // we just want a brief summary or either UP or DOWN
        boolean up = res.stream().noneMatch(r -> r.getState().equals(HealthCheck.State.DOWN));
        if (up) {
            sb.append("    \"status\": \"UP\"");
        } else {
            sb.append("    \"status\": \"DOWN\"");
        }
        List<HealthCheck.Result> checks = new ArrayList<>(res);
        if (!checks.isEmpty()) {
            sb.append(",\n");
            sb.append("    \"checks\": [\n");
            for (int i = 0; i < checks.size(); i++) {
                HealthCheck.Result d = checks.get(i);
                sb.append("        {\n");
                reportHealthCheck(sb, d);
                if (i < checks.size() - 1) {
                    sb.append("        },\n");
                } else {
                    sb.append("        }\n");
                }
            }
            sb.append("    ]\n");
        } else {
            sb.append("\n");
        }
    }

    private static void healthCheckDefault(StringBuilder sb, Collection<HealthCheck.Result> res) {
        // we just want a brief summary or either UP or DOWN
        boolean up = res.stream().noneMatch(r -> r.getState().equals(HealthCheck.State.DOWN));
        if (up) {
            sb.append("    \"status\": \"UP\"\n");
        } else {
            // when we are DOWN then grab the only downs
            List<HealthCheck.Result> down
                    = res.stream().filter(r -> r.getState().equals(HealthCheck.State.DOWN)).collect(Collectors.toList());
            sb.append("    \"status\": \"DOWN\",\n");
            sb.append("    \"checks\": [\n");
            for (int i = 0; i < down.size(); i++) {
                HealthCheck.Result d = down.get(i);
                sb.append("        {\n");
                reportHealthCheck(sb, d);
                if (i < down.size() - 1) {
                    sb.append("        },\n");
                } else {
                    sb.append("        }\n");
                }
            }
            sb.append("    ]\n");
        }
    }

    private static void reportHealthCheck(StringBuilder sb, HealthCheck.Result d) {
        sb.append("            \"name\": \"").append(d.getCheck().getId()).append("\",\n");
        sb.append("            \"status\": \"").append(d.getState()).append("\",\n");
        if (d.getError().isPresent()) {
            String msg = d.getError().get().getMessage();
            sb.append("            \"error-message\": \"").append(msg)
                    .append("\",\n");
        }
        if (d.getMessage().isPresent()) {
            sb.append("            \"message\": \"").append(d.getMessage().get()).append("\",\n");
        }
        if (d.getDetails() != null && !d.getDetails().isEmpty()) {
            // lets use sorted keys
            Iterator<String> it = new TreeSet<>(d.getDetails().keySet()).iterator();
            sb.append("            \"data\": {\n");
            while (it.hasNext()) {
                String k = it.next();
                Object v = d.getDetails().get(k);
                boolean last = !it.hasNext();
                sb.append("                 \"").append(k).append("\": \"").append(v).append("\"");
                if (!last) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("            }\n");
        }
    }

}
