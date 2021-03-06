/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.clustering.jgroups.subsystem;

import static org.jboss.as.clustering.jgroups.subsystem.RelayResourceDefinition.Attribute.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.value.Value;
import org.jgroups.JChannel;
import org.jgroups.protocols.FORK;
import org.jgroups.protocols.relay.RELAY2;
import org.jgroups.protocols.relay.config.RelayConfig;
import org.wildfly.clustering.jgroups.spi.RelayConfiguration;
import org.wildfly.clustering.jgroups.spi.RemoteSiteConfiguration;
import org.wildfly.clustering.service.Builder;
import org.wildfly.clustering.service.InjectedValueDependency;
import org.wildfly.clustering.service.ServiceNameProvider;
import org.wildfly.clustering.service.ValueDependency;

/**
 * @author Paul Ferraro
 */
public class RelayConfigurationBuilder extends AbstractProtocolConfigurationBuilder<RELAY2, RelayConfiguration> implements RelayConfiguration {

    private final ServiceNameProvider provider;
    private volatile List<ValueDependency<RemoteSiteConfiguration>> sites;
    private volatile String siteName = null;

    public RelayConfigurationBuilder(PathAddress address) {
        super(address.getLastElement().getValue());
        this.provider = new SingletonProtocolServiceNameProvider(address);
    }

    @Override
    public ServiceName getServiceName() {
        return this.provider.getServiceName();
    }

    @Override
    public ServiceBuilder<RelayConfiguration> build(ServiceTarget target) {
        ServiceBuilder<RelayConfiguration> builder = super.build(target);
        this.sites.forEach(dependency -> dependency.register(builder));
        return builder;
    }

    @Override
    public Builder<RelayConfiguration> configure(OperationContext context, ModelNode model) throws OperationFailedException {
        this.siteName = SITE.resolveModelAttribute(context, model).asString();

        PathAddress address = context.getCurrentAddress();
        Resource resource = context.readResource(PathAddress.EMPTY_ADDRESS);
        this.sites = resource.getChildren(RemoteSiteResourceDefinition.WILDCARD_PATH.getKey()).stream().map(entry -> new InjectedValueDependency<>(new RemoteSiteServiceNameProvider(address, entry.getPathElement()), RemoteSiteConfiguration.class)).collect(Collectors.toList());

        return super.configure(context, model);
    }

    @Override
    public RelayConfiguration getValue() {
        return this;
    }

    @Override
    public String getSiteName() {
        return this.siteName;
    }

    @Override
    public List<RemoteSiteConfiguration> getRemoteSites() {
        return this.sites.stream().map(Value::getValue).collect(Collectors.toList());
    }

    @Override
    public void accept(RELAY2 protocol) {
        String localSite = this.siteName;
        List<RemoteSiteConfiguration> remoteSites = this.getRemoteSites();
        List<String> sites = new ArrayList<>(remoteSites.size() + 1);
        sites.add(localSite);
        // Collect bridges, eliminating duplicates
        Map<String, RelayConfig.BridgeConfig> bridges = new HashMap<>();
        for (final RemoteSiteConfiguration remoteSite: remoteSites) {
            String siteName = remoteSite.getName();
            sites.add(siteName);
            String clusterName = remoteSite.getClusterName();
            RelayConfig.BridgeConfig bridge = new RelayConfig.BridgeConfig(clusterName) {
                @Override
                public JChannel createChannel() throws Exception {
                    JChannel channel = remoteSite.getChannelFactory().createChannel(siteName);
                    // Don't use FORK in bridge stack
                    channel.getProtocolStack().removeProtocol(FORK.class);
                    return channel;
                }
            };
            bridges.put(clusterName, bridge);
        }
        protocol.site(localSite);
        for (String site: sites) {
            RelayConfig.SiteConfig siteConfig = new RelayConfig.SiteConfig(site);
            protocol.addSite(site, siteConfig);
            if (site.equals(localSite)) {
                for (RelayConfig.BridgeConfig bridge: bridges.values()) {
                    siteConfig.addBridge(bridge);
                }
            }
        }
    }
}
