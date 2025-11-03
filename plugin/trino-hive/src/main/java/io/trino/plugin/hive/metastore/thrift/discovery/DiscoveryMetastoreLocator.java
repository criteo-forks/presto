/*
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

package io.trino.plugin.hive.metastore.thrift.discovery;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.google.inject.Inject;
import com.orbitz.consul.Consul;
import com.orbitz.consul.HealthClient;
import com.orbitz.consul.model.ConsulResponse;
import com.orbitz.consul.model.health.ServiceHealth;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.trino.spi.TrinoException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_METASTORE_ERROR;
import static java.util.Objects.requireNonNull;

public class DiscoveryMetastoreLocator
        implements MetastoreEndpointLocator
{
    private static final Logger log = Logger.get(DiscoveryMetastoreLocator.class);

    private static final String CONSUL_SCHEME = "consul";
    private static final String THRIFT_SCHEME = "thrift";

    private final Supplier<List<URI>> resolvedUriSupplier;

    @Inject
    public DiscoveryMetastoreLocator(DiscoveryMetastoreConfig config)
    {
        // basic error checks
        requireNonNull(config, "config is null");
        List<URI> metastoreUris = config.getMetastoreUris();
        checkArgument(!metastoreUris.isEmpty(), "metastoreUris must specify at least one URI");
        metastoreUris.forEach(DiscoveryMetastoreLocator::checkMetastoreUri);

        // build supplier with ttl
        final Duration ttl = config.getResolvedUrisTtl();
        this.resolvedUriSupplier = Suppliers.memoizeWithExpiration(
                () -> resolveUris(metastoreUris),
                Math.round(ttl.getValue(TimeUnit.MILLISECONDS)),
                TimeUnit.MILLISECONDS);
    }

    protected static void checkMetastoreUri(URI uri)
    {
        String scheme = uri.getScheme();
        checkArgument(!isNullOrEmpty(scheme), "metastoreUri scheme is missing: %s", uri);
        if (scheme.equalsIgnoreCase(CONSUL_SCHEME)) {
            checkArgument(!isNullOrEmpty(uri.getHost()), "Unspecified consul host, please use consul://consul-host:consul-port/service-name");
            checkArgument(uri.getPort() != -1, "Unspecified consul port, please use consul://consul-host:consul-port/service-name");
            checkArgument(!isNullOrEmpty(uri.getPath()), "Unspecified consul service, please use consul://consul-host:consul-port/service-name");
        }
        else {
            checkArgument(scheme.equals(THRIFT_SCHEME), "metastoreUri scheme must be thrift: %s", uri);
            checkArgument(uri.getHost() != null, "metastoreUri host is missing: %s", uri);
            checkArgument(uri.getPort() != -1, "metastoreUri port is missing: %s", uri);
        }
    }

    @Override
    public List<URI> resolvedUris()
    {
        return resolvedUriSupplier.get();
    }

    private static List<URI> resolveUris(List<URI> unresolvedUris)
    {
        final ImmutableList.Builder<URI> results = ImmutableList.builder();
        for (URI uri : unresolvedUris) {
            String scheme = uri.getScheme();
            if (scheme.equalsIgnoreCase(CONSUL_SCHEME)) {
                try {
                    final List<URI> resolved = resolveUsingConsul(uri);
                    log.info("Resolved consul uri %s, got %d endpoints", uri, resolved.size());
                    results.addAll(resolved);
                    if (!resolved.isEmpty()) {
                        // if we can resolve with consul we don't go further down the list (fallback uris)
                        break;
                    }
                }
                catch (Exception e) {
                    log.warn("Error resolving consul uri: " + uri, e);
                }
            }
            else {
                results.add(uri);
            }
        }

        return results.build();
    }

    private static List<URI> resolveUsingConsul(URI consulUri)
    {
        final String consulHost = consulUri.getHost();
        final String service = consulUri.getPath().substring(1); // strip leading slash
        final int consulPort = consulUri.getPort();
        final HostAndPort hostAndPort = HostAndPort.fromParts(consulHost, consulPort);
        final Consul consul = Consul.builder().withHostAndPort(hostAndPort).build();
        final HealthClient healthClient = consul.healthClient();
        final ConsulResponse<List<ServiceHealth>> result = healthClient.getHealthyServiceInstances(service);
        if (result == null) {
            throw new TrinoException(HIVE_METASTORE_ERROR, "Unable to query " + consulUri + " for service " + service);
        }

        List<URI> endpoints = new ArrayList<>();
        for (ServiceHealth sh : result.getResponse()) {
            String nodeHost = sh.getService().getAddress();
            if (nodeHost == null || nodeHost.isEmpty()) {
                nodeHost = sh.getNode().getAddress();
            }
            int nodePort = sh.getService().getPort();
            if (nodeHost != null && nodePort > 0) {
                endpoints.add(URI.create(String.format(THRIFT_SCHEME + "://%s:%d", nodeHost, nodePort)));
            }
        }

        if (endpoints.isEmpty()) {
            throw new TrinoException(HIVE_METASTORE_ERROR, "No healthy Hive Metastore instances found in Consul for service " + service);
        }
        return endpoints;
    }
}
