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

import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.trino.plugin.hive.metastore.thrift.ThriftMetastoreClient;
import io.trino.plugin.hive.metastore.thrift.ThriftMetastoreClientFactory;
import io.trino.plugin.hive.metastore.thrift.TokenAwareMetastoreClientFactory;
import io.trino.spi.TrinoException;
import org.apache.thrift.TException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static io.trino.plugin.hive.HiveErrorCode.HIVE_METASTORE_ERROR;
import static java.util.Objects.requireNonNull;

public class DiscoveryTokenAwareMetastoreClientFactory
        implements TokenAwareMetastoreClientFactory
{
    private static final Logger log = Logger.get(DiscoveryTokenAwareMetastoreClientFactory.class);

    private final MetastoreEndpointLocator locator;
    private final ThriftMetastoreClientFactory baseFactory;
    private final DiscoveryMetastoreConfig config;

    @Inject
    public DiscoveryTokenAwareMetastoreClientFactory(
            MetastoreEndpointLocator locator,
            ThriftMetastoreClientFactory baseFactory,
            DiscoveryMetastoreConfig config)
    {
        this.locator = requireNonNull(locator, "locator is null");
        this.baseFactory = requireNonNull(baseFactory, "baseFactory is null");
        this.config = requireNonNull(config, "config is null");
    }

    @Override
    public ThriftMetastoreClient createMetastoreClient(Optional<String> delegationToken)
    {
        List<URI> resolvedUris = new ArrayList<>(locator.resolvedUris());
        Collections.shuffle(resolvedUris);

        if (resolvedUris.isEmpty()) {
            throw new RuntimeException("No Hive Metastore endpoints resolved from discovery");
        }

        TException lastException = null;
        for (URI uri : resolvedUris) {
            try {
                ThriftMetastoreClient client = baseFactory.create(uri, delegationToken);

                if (config.getMetastoreUsername() != null && !config.getMetastoreUsername().isEmpty()) {
                    try {
                        client.setUGI(config.getMetastoreUsername());
                    }
                    catch (Throwable t) {
                        log.warn(t, "Failed to set UGI on metastore client");
                    }
                }

                return client;
            }
            catch (Throwable t) {
                lastException = new TException("Failed to connect to metastore at " + resolvedUris, t);
                log.warn("Metastore endpoint failed: %s (%s)", uri, t.getMessage());
            }
        }

        throw new TrinoException(HIVE_METASTORE_ERROR, "Failed connecting to Hive metastore using any of the URI's: " + resolvedUris, lastException);
    }
}
