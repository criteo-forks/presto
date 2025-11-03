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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.linkedin.coral.calcite.$internal.org.codehaus.commons.nullanalysis.NotNull;
import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.units.Duration;
import io.airlift.units.MinDuration;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Iterables.transform;

public class DiscoveryMetastoreConfig
{
    private static final Splitter SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    private List<URI> metastoreUris;
    private Duration resolvedUrisTtl = new Duration(3, TimeUnit.MINUTES);
    private String metastoreUsername;

    @Config("hive.metastore.uri")
    @ConfigDescription("Comma separated list of URI's. Supports a dynamic consul URI of the form consul://consul-host:consul-port/service-name")
    public DiscoveryMetastoreConfig setMetastoreUris(String metastoreUris)
    {
        this.metastoreUris = ImmutableList.copyOf(transform(SPLITTER.split(metastoreUris), URI::create));
        return this;
    }

    @NotNull
    public List<URI> getMetastoreUris()
    {
        return metastoreUris;
    }

    @Config("hive.metastore.resolved-uris-ttl")
    public DiscoveryMetastoreConfig setResolvedUrisTtl(Duration ttl)
    {
        this.resolvedUrisTtl = ttl;
        return this;
    }

    @NotNull
    @MinDuration("0ms")
    public Duration getResolvedUrisTtl()
    {
        return resolvedUrisTtl;
    }

    @Config("hive.metastore.username")
    @ConfigDescription("Optional username for accessing the Hive metastore")
    public DiscoveryMetastoreConfig setMetastoreUsername(String metastoreUsername)
    {
        this.metastoreUsername = metastoreUsername;
        return this;
    }

    public String getMetastoreUsername()
    {
        return metastoreUsername;
    }
}
