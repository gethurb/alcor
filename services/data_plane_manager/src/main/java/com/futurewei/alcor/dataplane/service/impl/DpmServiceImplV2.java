/*
MIT License
Copyright(c) 2020 Futurewei Cloud

    Permission is hereby granted,
    free of charge, to any person obtaining a copy of this software and associated documentation files(the "Software"), to deal in the Software without restriction,
    including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and / or sell copies of the Software, and to permit persons
    to whom the Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
    WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/
package com.futurewei.alcor.dataplane.service.impl;

import com.futurewei.alcor.common.db.CacheException;
import com.futurewei.alcor.common.tracer.Tracer;
import com.futurewei.alcor.dataplane.cache.LocalCache;
import com.futurewei.alcor.dataplane.cache.SubnetPortsCache;
import com.futurewei.alcor.dataplane.cache.VpcGatewayInfoCache;
import com.futurewei.alcor.dataplane.client.DataPlaneClient;
import com.futurewei.alcor.dataplane.client.ZetaGatewayClient;
import com.futurewei.alcor.dataplane.config.Config;
import com.futurewei.alcor.dataplane.entity.*;
import com.futurewei.alcor.dataplane.exception.NeighborInfoNotFound;
import com.futurewei.alcor.dataplane.exception.PortBindingHostIpNotFound;
import com.futurewei.alcor.dataplane.exception.UnknownResourceType;
import com.futurewei.alcor.dataplane.service.DpmService;
import com.futurewei.alcor.schema.*;
import com.futurewei.alcor.web.entity.dataplane.*;
import com.futurewei.alcor.web.entity.dataplane.v2.NetworkConfiguration;
import com.futurewei.alcor.web.entity.port.PortHostInfo;
import com.futurewei.alcor.web.entity.route.InternalRouterInfo;
import com.futurewei.alcor.web.entity.route.InternalSubnetRoutingTable;
import com.futurewei.alcor.web.entity.subnet.InternalSubnetPorts;
import com.futurewei.alcor.web.restclient.DataPlaneManagerRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.futurewei.alcor.dataplane.service.impl.ResourceService.FORMAT_REVISION_NUMBER;
import static com.futurewei.alcor.dataplane.service.impl.ResourceService.HOST_DVR_MAC;

@Service
@ConditionalOnProperty(prefix = "protobuf.goal-state-message", name = "version", havingValue = "102")
public class DpmServiceImplV2 implements DpmService {
    private static final Logger LOG = LoggerFactory.getLogger(DpmServiceImplV2.class);
    private static final boolean USE_PULSAR_CLIENT = false;

    private int goalStateMessageVersion;
    private DataPlaneManagerRestClient dataPlaneManagerRestClient;

    @Value("${zetaGateway.enabled:false}")
    private boolean zetaGatwayEnabled;

    @Autowired
    private ZetaGatewayClient zetaGatewayClient;

    @Autowired
    private LocalCache localCache;

    @Autowired
    private VpcGatewayInfoCache gatewayInfoCache;

    @Autowired
    private SubnetPortsCache subnetPortsCache;

    @Autowired
    private DataPlaneClient<UnicastGoalStateV2, MulticastGoalStateV2> grpcDataPlaneClient;

    @Autowired
    private DataPlaneClient<UnicastGoalStateV2, MulticastGoalStateV2> pulsarDataPlaneClient;

    @Autowired
    private VpcService vpcService;

    @Autowired
    private SubnetService subnetService;

    @Autowired
    private PortService portService;

    @Autowired
    private ZetaPortService zetaPortService;

    @Autowired
    private NeighborService neighborService;

    @Autowired
    private SecurityGroupService securityGroupService;

    @Autowired
    private DhcpService dhcpService;

    @Autowired
    private RouterService routerService;

    @Autowired
    private DpmServiceImplV2(Config globalConfig) {
        this.goalStateMessageVersion = globalConfig.goalStateMessageVersion;
    }

    private UnicastGoalStateV2 buildUnicastGoalState(NetworkConfiguration networkConfig, String hostIp,
                                                   List<InternalPortEntity> portEntities,
                                                   MulticastGoalStateV2 multicastGoalState) throws Exception {
        UnicastGoalStateV2 unicastGoalState = new UnicastGoalStateV2();
        unicastGoalState.setHostIp(hostIp);
        unicastGoalState.getGoalStateBuilder().setFormatVersion(this.goalStateMessageVersion);

        if (portEntities != null && portEntities.size() > 0) {
            portService.buildPortState(networkConfig, portEntities, unicastGoalState);
        }

        vpcService.buildVpcStates(networkConfig, unicastGoalState);
        subnetService.buildSubnetStates(networkConfig, unicastGoalState, multicastGoalState);
        neighborService.buildNeighborStates(networkConfig, hostIp, unicastGoalState, multicastGoalState);
        securityGroupService.buildSecurityGroupStates(networkConfig, unicastGoalState);
        dhcpService.buildDhcpStates(networkConfig, unicastGoalState);
        routerService.buildRouterStates(networkConfig, unicastGoalState, multicastGoalState);
        patchGoalstateForNeighbor(networkConfig, unicastGoalState);

        unicastGoalState.setGoalState(unicastGoalState.getGoalStateBuilder().build());
        unicastGoalState.setGoalStateBuilder(null);
        multicastGoalState.setGoalState(multicastGoalState.getGoalStateBuilder().build());
        multicastGoalState.setGoalStateBuilder(null);

        return unicastGoalState;
    }

    private void patchGoalstateForNeighbor(NetworkConfiguration networkConfig, UnicastGoalStateV2 unicastGoalState) throws CacheException {
        Map<String, Neighbor.NeighborState> neighborStatesMap = unicastGoalState.getGoalStateBuilder().getNeighborStatesMap();
        for (Map.Entry<String, Neighbor.NeighborState> neighborStateEntry : neighborStatesMap.entrySet()) {
            List<Neighbor.NeighborConfiguration.FixedIp> fixedIps = neighborStateEntry.getValue().getConfiguration().getFixedIpsList();
            for (Neighbor.NeighborConfiguration.FixedIp fixedIp : fixedIps) {
                if (fixedIp != null && fixedIp.getNeighborType().equals(Neighbor.NeighborType.L3)) {
                    String subnetId = fixedIp.getSubnetId();
                    InternalSubnetPorts subnetEntity = subnetPortsCache.getSubnetPorts(subnetId);
                    if (subnetEntity != null) {
                        if (unicastGoalState.getGoalStateBuilder().getSubnetStatesMap().entrySet().stream()
                                .filter(e -> e.getValue().getConfiguration().getId().equals(subnetEntity.getSubnetId()))
                                .findFirst().orElse(null) == null) {
                            Subnet.SubnetConfiguration.Builder subnetConfigBuilder = Subnet.SubnetConfiguration.newBuilder();
                            subnetConfigBuilder.setRevisionNumber(FORMAT_REVISION_NUMBER);
                            subnetConfigBuilder.setId(subnetEntity.getSubnetId());
                            subnetConfigBuilder.setVpcId(subnetEntity.getVpcId());
                            subnetConfigBuilder.setName(subnetEntity.getName());
                            subnetConfigBuilder.setCidr(subnetEntity.getCidr());
                            subnetConfigBuilder.setTunnelId(subnetEntity.getTunnelId());

                            Subnet.SubnetConfiguration.Gateway.Builder gatewayBuilder = Subnet.SubnetConfiguration.Gateway.newBuilder();
                            gatewayBuilder.setIpAddress(subnetEntity.getGatewayPortIp());
                            gatewayBuilder.setMacAddress(subnetEntity.getGatewayPortMac());
                            subnetConfigBuilder.setGateway(gatewayBuilder.build());

                            if (subnetEntity.getDhcpEnable() != null) {
                                subnetConfigBuilder.setDhcpEnable(subnetEntity.getDhcpEnable());
                            }

                            // TODO: need to set DNS based on latest contract

                            Subnet.SubnetState.Builder subnetStateBuilder = Subnet.SubnetState.newBuilder();
                            subnetStateBuilder.setOperationType(Common.OperationType.INFO);
                            subnetStateBuilder.setConfiguration(subnetConfigBuilder.build());
                            unicastGoalState.getGoalStateBuilder().putSubnetStates(subnetEntity.getSubnetId(), subnetStateBuilder.build());

                            Router.RouterConfiguration.SubnetRoutingTable.Builder subnetRoutingTableBuilder = Router.RouterConfiguration.SubnetRoutingTable.newBuilder();
                            subnetRoutingTableBuilder.setSubnetId(subnetEntity.getSubnetId());

                            List<Router.RouterConfiguration.SubnetRoutingTable> subnetRoutingTablesList = new ArrayList<>();
                            subnetRoutingTablesList.add(subnetRoutingTableBuilder.build());

                            Goalstate.GoalStateV2.Builder goalStateBuilder = unicastGoalState.getGoalStateBuilder();
                            List<Router.RouterState> routerStatesBuilders = new ArrayList<>(goalStateBuilder.getRouterStatesMap().values());

                            if (routerStatesBuilders != null && routerStatesBuilders.size() > 0) {
                                Router.RouterState routerState = routerStatesBuilders.get(0);
                                subnetRoutingTablesList.addAll(routerState.
                                        getConfiguration().
                                        getSubnetRoutingTablesList());
                                goalStateBuilder.removeSubnetStates(new ArrayList<String>(goalStateBuilder.getRouterStatesMap().keySet()).get(0));
                            }

                            String routerId = subnetEntity.getRouterId();
                            // If subnet has attached to a router (test scenario #4), we just use the routerId in the subnet.
                            // Otherwise, we need to get router_state in the networkConfig for test scenario #5.
                            if (routerId == null) {
                                List<InternalRouterInfo> internalRouterInfos = networkConfig.getInternalRouterInfos();
                                for (InternalRouterInfo internalRouterInfo : internalRouterInfos) {
                                    routerId = internalRouterInfo.getRouterConfiguration().getId();
                                    if (routerId != null) break;
                                }
                            }

                            // Add subnet to router_state
                            Router.RouterConfiguration.Builder routerConfigBuilder = Router.RouterConfiguration.newBuilder();
                            routerConfigBuilder.setRevisionNumber(FORMAT_REVISION_NUMBER);
                            routerConfigBuilder.setHostDvrMacAddress(HOST_DVR_MAC);
                            routerConfigBuilder.setId(routerId);
                            routerConfigBuilder.addAllSubnetRoutingTables(subnetRoutingTablesList);
                            Router.RouterState.Builder routerStateBuilder = Router.RouterState.newBuilder();
                            routerStateBuilder.setConfiguration(routerConfigBuilder.build());
                            unicastGoalState.getGoalStateBuilder().putRouterStates(routerId, routerStateBuilder.build());
                        }
                    }
                }
            }
        }
    }

    private List<String> doCreatePortConfiguration(NetworkConfiguration networkConfig,
                                                   Map<String, List<InternalPortEntity>> hostPortEntities,
                                                   DataPlaneClient dataPlaneClient) throws Exception {
        List<UnicastGoalStateV2> unicastGoalStates = new ArrayList<>();
        MulticastGoalStateV2 multicastGoalState = new MulticastGoalStateV2();

        ZetaPortGoalState zetaPortsGoalState = new ZetaPortGoalState();
        List<String> failedZetaPorts = new ArrayList<>();

        for (Map.Entry<String, List<InternalPortEntity>> entry : hostPortEntities.entrySet()) {
            String hostIp = entry.getKey();
            List<InternalPortEntity> portEntities = entry.getValue();

            if (zetaGatwayEnabled) {
                if (portEntities != null && portEntities.size() > 0) {
                    zetaPortService.buildPortState(networkConfig, portEntities, zetaPortsGoalState);
                }
            }

            unicastGoalStates.add(buildUnicastGoalState(
                    networkConfig, hostIp, portEntities, multicastGoalState));
        }
        // portEntities in the same unicastGoalStates should have the same opType

//        TODO: Enable zetaGatewayClient support GSV2
//        if (zetaGatwayEnabled && zetaPortsGoalState.getPortEntities().size() > 0) {
//            return zetaGatewayClient.sendGoalStateToZetaAcA(unicastGoalStates, multicastGoalState, dataPlaneClient, zetaPortsGoalState, failedZetaPorts);
//        } else {
//            return dataPlaneClient.sendGoalStates(unicastGoalStates, multicastGoalState);
//        }

        return dataPlaneClient.sendGoalStates(unicastGoalStates, multicastGoalState);
    }

    /**
     * This method processes the network configuration where the resource type is PORT.
     * The NetworkConfiguration may contain multiple port entities, and these port
     * entities may need to be sent to multiple hosts, Other configurations related to port
     * also need to be processed, such as routing configuration and neighbor configuration.
     * Here we build one UnicastGoalState for each host and put the information that it needs
     * into it. If the same information needs to be sent to multiple host, in order to
     * improve the transmission performance, only one MulticastGoalState is constructed and
     * broadcast it to the all target  hosts.
     * @param networkConfig The networkConfig may contain multiple port entities and related Configuration
     * @return Hosts that failed to send GoalState
     * @throws Exception Process exceptions and send exceptions
     */
    private List<String> processPortConfiguration(NetworkConfiguration networkConfig) throws Exception {
        Map<String, List<InternalPortEntity>> grpcHostPortEntities = new HashMap<>();
        Map<String, List<InternalPortEntity>> pulsarHostPortEntities = new HashMap<>();

        for (InternalPortEntity portEntity : networkConfig.getPortEntities()) {
            if (portEntity.getBindingHostIP() == null) {
                throw new PortBindingHostIpNotFound();
            }

            if (zetaGatwayEnabled) {
                zetaGatewayClient.enableZetaGatewayForPort(portEntity);
            }

            boolean fastPath = true;
            if (portEntity.getFastPath() != null && portEntity.getFastPath() == false) {
                fastPath = portEntity.getFastPath();
            }
            if (fastPath) {
                if (!grpcHostPortEntities.containsKey(portEntity.getBindingHostIP())) {
                    grpcHostPortEntities.put(portEntity.getBindingHostIP(), new ArrayList<>());
                }
                grpcHostPortEntities.get(portEntity.getBindingHostIP()).add(portEntity);
            } else {
                if (!pulsarHostPortEntities.containsKey(portEntity.getBindingHostIP())) {
                    pulsarHostPortEntities.put(portEntity.getBindingHostIP(), new ArrayList<>());
                }
                pulsarHostPortEntities.get(portEntity.getBindingHostIP()).add(portEntity);
            }
        }

        List<String> statusList = new ArrayList<>();

        if (grpcHostPortEntities.size() != 0) {
            statusList.addAll(doCreatePortConfiguration(
                    networkConfig, grpcHostPortEntities, grpcDataPlaneClient));
        }

        if (pulsarHostPortEntities.size() != 0) {
            statusList.addAll(doCreatePortConfiguration(
                    networkConfig, pulsarHostPortEntities, pulsarDataPlaneClient));
        }

        localCache.updateLocalCache(networkConfig);

        return statusList;
    }

    private Subnet.SubnetState.Builder constructSubnetState(InternalSubnetPorts subnetEntity) {
        Subnet.SubnetState.Builder subnetStateBuilder = Subnet.SubnetState.newBuilder();

        if (subnetEntity != null) {
            Subnet.SubnetConfiguration.Builder subnetConfigBuilder = Subnet.SubnetConfiguration.newBuilder();
            subnetConfigBuilder.setRevisionNumber(FORMAT_REVISION_NUMBER);
            subnetConfigBuilder.setId(subnetEntity.getSubnetId());
            subnetConfigBuilder.setVpcId(subnetEntity.getVpcId());
            subnetConfigBuilder.setName(subnetEntity.getName());
            subnetConfigBuilder.setCidr(subnetEntity.getCidr());
            subnetConfigBuilder.setTunnelId(subnetEntity.getTunnelId());

            Subnet.SubnetConfiguration.Gateway.Builder gatewayBuilder = Subnet.SubnetConfiguration.Gateway.newBuilder();
            gatewayBuilder.setIpAddress(subnetEntity.getGatewayPortIp());
            gatewayBuilder.setMacAddress(subnetEntity.getGatewayPortMac());
            subnetConfigBuilder.setGateway(gatewayBuilder.build());

            if (subnetEntity.getDhcpEnable() != null) {
                subnetConfigBuilder.setDhcpEnable(subnetEntity.getDhcpEnable());
            }

            // TODO: need to set DNS based on latest contract
            subnetStateBuilder.setOperationType(Common.OperationType.INFO);
            subnetStateBuilder.setConfiguration(subnetConfigBuilder.build());
        }

        return subnetStateBuilder;
    }

    private void patchGoalstateForRouterSubnet(NetworkConfiguration networkConfig, Map<String, List<String>> hostsSubnets, MulticastGoalStateV2 multicastGoalState) throws CacheException {
        for (Map.Entry<String, List<String>> entry: hostsSubnets.entrySet()) {
            for (String subnetId : entry.getValue()) {
                InternalSubnetPorts subnetEntity = subnetPortsCache.getSubnetPorts(subnetId);
                if (subnetEntity != null) {
                    if (multicastGoalState.getGoalStateBuilder().getSubnetStatesMap().values().stream()
                            .filter(e -> e.getConfiguration().getId().equals(subnetEntity.getSubnetId()))
                            .findFirst().orElse(null) == null) {
                        Subnet.SubnetState.Builder subnetStateBuilder = constructSubnetState(subnetEntity);
                        multicastGoalState.getGoalStateBuilder().putSubnetStates(subnetStateBuilder.getConfiguration().getId(), subnetStateBuilder.build());

                        // Add subnet to router_state
                        Goalstate.GoalStateV2.Builder goalStateBuilder = multicastGoalState.getGoalStateBuilder();
                        Router.RouterState.Builder routerStateBuilder = constructRouterState(networkConfig, subnetEntity, goalStateBuilder);
                        multicastGoalState.getGoalStateBuilder().putRouterStates(routerStateBuilder.getConfiguration().getId(), routerStateBuilder.build());
                    }
                }
            }
        }
    }

    private Router.RouterState.Builder constructRouterState(NetworkConfiguration networkConfig, InternalSubnetPorts subnetEntity, Goalstate.GoalStateV2.Builder goalStateBuilder) {
        Router.RouterState.Builder routerStateBuilder = Router.RouterState.newBuilder();
        if (subnetEntity != null) {
            Router.RouterConfiguration.SubnetRoutingTable.Builder subnetRoutingTableBuilder = Router.RouterConfiguration.SubnetRoutingTable.newBuilder();
            subnetRoutingTableBuilder.setSubnetId(subnetEntity.getSubnetId());

            List<Router.RouterConfiguration.SubnetRoutingTable> subnetRoutingTablesList = new ArrayList<>();
            subnetRoutingTablesList.add(subnetRoutingTableBuilder.build());
            List<Router.RouterState> routerStatesBuilders = new ArrayList<Router.RouterState>(goalStateBuilder.getRouterStatesMap().values());

            if (routerStatesBuilders != null && routerStatesBuilders.size() > 0) {
                Router.RouterState routerState = routerStatesBuilders.get(0);
                subnetRoutingTablesList.addAll(routerState.
                        getConfiguration().
                        getSubnetRoutingTablesList());
                goalStateBuilder.removeSubnetStates(new ArrayList<String>(goalStateBuilder.getRouterStatesMap().keySet()).get(0));
            }

            String routerId = subnetEntity.getRouterId();
            // If subnet has attached to a router (test scenario #4), we just use the routerId in the subnet.
            // Otherwise, we need to get router_state in the networkConfig for test scenario #5.
            if (routerId == null) {
                List<InternalRouterInfo> internalRouterInfos = networkConfig.getInternalRouterInfos();
                for (InternalRouterInfo internalRouterInfo : internalRouterInfos) {
                    routerId = internalRouterInfo.getRouterConfiguration().getId();
                    if (routerId != null) break;
                }
            }
            Router.RouterConfiguration.Builder routerConfigBuilder = Router.RouterConfiguration.newBuilder();
            routerConfigBuilder.setRevisionNumber(FORMAT_REVISION_NUMBER);
            routerConfigBuilder.setHostDvrMacAddress(HOST_DVR_MAC);
            routerConfigBuilder.setId(routerId);
            routerConfigBuilder.addAllSubnetRoutingTables(subnetRoutingTablesList);
            routerStateBuilder.setConfiguration(routerConfigBuilder.build());
        }
        return routerStateBuilder;
    }

    @Tracer
    private void rebuildRouterState(Goalstate.GoalStateV2.Builder goalStateBuilder, Goalstate.GoalStateV2.Builder newGoalState) {
        List<Router.RouterConfiguration.SubnetRoutingTable> subnetRoutingTables = new ArrayList<>();
        for (Router.RouterConfiguration.SubnetRoutingTable subnetRoutingTable : newGoalState.getRouterStatesMap().entrySet().iterator().next().getValue().getConfiguration().getSubnetRoutingTablesList()) {
            Router.RouterConfiguration.SubnetRoutingTable.Builder subnetRoutingTableBuilder = Router.RouterConfiguration.SubnetRoutingTable.newBuilder();
            subnetRoutingTableBuilder.setSubnetId(subnetRoutingTable.getSubnetId());
            subnetRoutingTables.add(subnetRoutingTableBuilder.build());
        }

        List<Router.RouterState> routerStatesBuilders = new ArrayList<Router.RouterState>(goalStateBuilder.getRouterStatesMap().values());
        if (routerStatesBuilders != null && routerStatesBuilders.size() > 0) {
            Router.RouterState routerState = routerStatesBuilders.get(0);
            subnetRoutingTables.addAll(routerState.
                    getConfiguration().
                    getSubnetRoutingTablesList());
            String routerId = routerState.getConfiguration().getId();
            String hostDvrMac = routerState.getConfiguration().getHostDvrMacAddress();
            goalStateBuilder.removeSubnetStates(new ArrayList<String>(goalStateBuilder.getRouterStatesMap().keySet()).get(0));

            Router.RouterConfiguration.Builder routerConfigBuilder = Router.RouterConfiguration.newBuilder();
            routerConfigBuilder.setRevisionNumber(FORMAT_REVISION_NUMBER);

            //TODO: where does the hostDvrMacAddress come from ?
            routerConfigBuilder.setHostDvrMacAddress(hostDvrMac);
            routerConfigBuilder.setId(routerId);
            routerConfigBuilder.addAllSubnetRoutingTables(subnetRoutingTables);
            Router.RouterState.Builder routerStateBuilder = Router.RouterState.newBuilder();
            routerStateBuilder.setConfiguration(routerConfigBuilder.build());
            goalStateBuilder.putRouterStates(routerStateBuilder.getConfiguration().getId(), routerStateBuilder.build());
        }
    }

    /**
     * This method get neighbor information from NetworkConfiguration, then build one
     * UnicastGoalState for each host and fill in the neighbor information it needs,
     * and finally send all UnicastGoalState to the relevant hosts.
     * @param networkConfig Network configuration with neighbor configuration
     * @return Hosts that failed to send GoalState
     * @throws Exception Process exceptions and send exceptions
     */
    private List<String> processNeighborConfiguration(NetworkConfiguration networkConfig) throws Exception {
        Map<String, NeighborInfo> neighborInfos = networkConfig.getNeighborInfos();
        Map<String, List<NeighborEntry>> neighborTable = networkConfig.getNeighborTable();
        List<UnicastGoalStateV2> unicastGoalStates = new ArrayList<>();
        MulticastGoalStateV2 multicastGoalState = new MulticastGoalStateV2();
        Map<String, List<String>> hostsSubnets = new HashMap<>();

        if (neighborTable == null || neighborInfos == null) {
            throw new NeighborInfoNotFound();
            //return new ArrayList<>();
        }

        Map<String, List<NeighborInfo>> hostNeighbors = new HashMap<>();
        for (Map.Entry<String, List<NeighborEntry>> entry: neighborTable.entrySet()) {
            String portIp = entry.getKey();
            NeighborInfo localInfo = neighborInfos.get(portIp);
            if (localInfo == null) {
                throw new NeighborInfoNotFound();
            }

            String hostIp = localInfo.getHostIp();
            if (!hostNeighbors.containsKey(hostIp)) {
                hostNeighbors.put(hostIp, new ArrayList<>());
                hostsSubnets.put(hostIp, new ArrayList<>());
            }
            hostsSubnets.get(hostIp).add(localInfo.getSubnetId());

            List<NeighborEntry> neighborEntries = entry.getValue();
            for (NeighborEntry neighborEntry: neighborEntries) {
                String neighborIp = neighborEntry.getNeighborIp();
                NeighborInfo neighborInfo = neighborInfos.get(neighborIp);
                if (neighborInfo == null) {
                    throw new NeighborInfoNotFound();
                }
                if (!Objects.equals(neighborEntry.getNeighborType().name(), "L2")) {
                    hostNeighbors.get(hostIp).add(neighborInfo);
                    multicastGoalState.getHostIps().add(neighborInfo.getHostIp());
                }
            }

            if (multicastGoalState.getHostIps().size() <= 0) {
                // return new ArrayList<>();
                continue;
            }
            //Add neighborInfo to multicastGoalState
            Neighbor.NeighborState neighborState = neighborService.buildNeighborState(
                    NeighborEntry.NeighborType.L3, localInfo, networkConfig.getOpType());
            multicastGoalState.getGoalStateBuilder().putNeighborStates(neighborState.getConfiguration().getId(), neighborState);
            UnicastGoalStateV2 unicastGoalStateTemp = new UnicastGoalStateV2();
            unicastGoalStateTemp.getGoalStateBuilder().putNeighborStates(neighborState.getConfiguration().getId(), neighborState);
            patchGoalstateForNeighbor(networkConfig, unicastGoalStateTemp);
            if (unicastGoalStateTemp.getGoalStateBuilder().getRouterStatesMap() != null &&
                    unicastGoalStateTemp.getGoalStateBuilder().getRouterStatesMap().size() > 0 ) {
                multicastGoalState.getGoalStateBuilder().putAllSubnetStates(unicastGoalStateTemp.getGoalStateBuilder().getSubnetStatesMap());
            }
            if (unicastGoalStateTemp.getGoalStateBuilder().getRouterStatesMap() != null &&
                    unicastGoalStateTemp.getGoalStateBuilder().getRouterStatesMap().size() > 0) {
                multicastGoalState.getGoalStateBuilder().putAllRouterStates(unicastGoalStateTemp.getGoalStateBuilder().getRouterStatesMap());
            }
        }

        boolean patchRouterSubnetStates = false;
        for (Map.Entry<String, List<NeighborInfo>> entry: hostNeighbors.entrySet()) {
            String hostIp = entry.getKey();
            List<NeighborInfo> hostNeighborInfos = entry.getValue();

            if (hostNeighborInfos.size() <= 0) {
                multicastGoalState.getHostIps().add(hostIp);
                patchRouterSubnetStates = true;
            }

            /**
             * At present, there are only L3 neighbors in the neighbor table,
             * and the processing of L2 neighbors should be considered in the future.
             */
            for (NeighborInfo neighborInfo: hostNeighborInfos) {
                Neighbor.NeighborState neighborState = neighborService.buildNeighborState(
                        NeighborEntry.NeighborType.L3,
                        neighborInfo,
                        networkConfig.getOpType());

                UnicastGoalStateV2 unicastGoalState = new UnicastGoalStateV2();
                //unicastGoalState.setHostIp(neighborInfo.getHostIp());
                unicastGoalState.setHostIp(hostIp);
                unicastGoalState.getGoalStateBuilder().putNeighborStates(neighborState.getConfiguration().getId(), neighborState);

                // use unicastGoalStateTemp object to get patchGoalStates for neighborState update
                // unicasGoalStateTemp will include subnet_states and a consolidated router_state based on the current neighborState
                UnicastGoalStateV2 unicastGoalStateTemp = new UnicastGoalStateV2();
                unicastGoalStateTemp.getGoalStateBuilder().putNeighborStates(neighborState.getConfiguration().getId(), neighborState);
                patchGoalstateForNeighbor(networkConfig, unicastGoalStateTemp);

                unicastGoalState.getGoalStateBuilder().putAllSubnetStates(unicastGoalStateTemp.getGoalStateBuilder().getSubnetStatesMap());
                unicastGoalState.getGoalStateBuilder().putAllRouterStates(unicastGoalStateTemp.getGoalStateBuilder().getRouterStatesMap());
                unicastGoalState.getGoalStateBuilder().putAllSubnetStates(multicastGoalState.getGoalStateBuilder().getSubnetStatesMap());
                rebuildRouterState(unicastGoalState.getGoalStateBuilder(), multicastGoalState.getGoalStateBuilder());
                multicastGoalState.getGoalStateBuilder().putAllSubnetStates(unicastGoalStateTemp.getGoalStateBuilder().getSubnetStatesMap());
                rebuildRouterState(multicastGoalState.getGoalStateBuilder(), unicastGoalStateTemp.getGoalStateBuilder());
                unicastGoalStates.add(unicastGoalState);
            }
        }

        /**
         * The flag patchRouterSubnetStates is to fix issue #686 (issue from test scenario 4.5)
         */
        if (patchRouterSubnetStates) {
            patchGoalstateForRouterSubnet(networkConfig, hostsSubnets, multicastGoalState);
        }

        multicastGoalState.setGoalState(multicastGoalState.getGoalStateBuilder().build());
        multicastGoalState.setGoalStateBuilder(null);
        unicastGoalStates.stream().forEach(u -> {
            u.setGoalState(u.getGoalStateBuilder().build());
            u.setGoalStateBuilder(null);
        });

        if (USE_PULSAR_CLIENT) {
            return pulsarDataPlaneClient.sendGoalStates(unicastGoalStates, multicastGoalState);
        }

        return grpcDataPlaneClient.sendGoalStates(unicastGoalStates, multicastGoalState);
    }

    private List<String> processSecurityGroupConfiguration(NetworkConfiguration networkConfig) throws Exception {
        return new ArrayList<>();
    }

    /**
     * This method get the subnet routing configuration from the NetworkConfiguration,
     * then find the host information of all the virtual machines under the subnet from
     * the local cache according to the subnet id, build one UnicastGoalState for each
     * host and fill it with the routing information it needs, and finally send all the
     * UnicastGoalState to the relevant hosts.
     * @param networkConfig Network configuration with router configuration
     * @return Hosts that failed to send GoalState
     * @throws Exception Process exceptions and send exceptions
     */
    private List<String> processRouterConfiguration(NetworkConfiguration networkConfig) throws Exception {
        List<InternalRouterInfo> internalRouterInfos = networkConfig.getInternalRouterInfos();
        MulticastGoalStateV2 multicastGoalState = new MulticastGoalStateV2();

        if (internalRouterInfos == null) {
            //throw new RouterInfoInvalid();
            return new ArrayList<>();
        }

        Map<String, UnicastGoalStateV2> unicastGoalStateMap = new HashMap<>();
        for (InternalRouterInfo routerInfo: internalRouterInfos) {
            List<InternalSubnetRoutingTable> subnetRoutingTables =
                    routerInfo.getRouterConfiguration().getSubnetRoutingTables();
            //if (subnetRoutingTables == null) {
            //    throw new RouterInfoInvalid();
            //}
            if (subnetRoutingTables != null) {
                for (InternalSubnetRoutingTable subnetRoutingTable : subnetRoutingTables) {
                    String subnetId = subnetRoutingTable.getSubnetId();

                    InternalSubnetPorts subnetPorts = localCache.getSubnetPorts(subnetId);
                    if (subnetPorts == null) {
                        //throw new SubnetPortsNotFound();
                        //return new ArrayList<>();
                        continue;
                    }
                    Set<String> ips = new HashSet<>();
                    subnetRoutingTable.getRoutingRules().forEach(routingRule -> {ips.add(routingRule.getNextHopIp());});
                    List<Neighbor.NeighborState> neighbors = neighborService.getAllNeighbors(ips) ;

                    for (PortHostInfo portHostInfo : subnetPorts.getPorts()) {
                        String hostIp = portHostInfo.getHostIp();
                        UnicastGoalStateV2 unicastGoalState = unicastGoalStateMap.get(hostIp);
                        if (unicastGoalState == null) {
                            unicastGoalState = new UnicastGoalStateV2();
                            unicastGoalState.setHostIp(hostIp);
                            unicastGoalStateMap.put(hostIp, unicastGoalState);
                            for (Neighbor.NeighborState neighbor : neighbors)
                            {
                                // neighbor can be NULL, at least in S5, so skip it
                                if (neighbor == null)
                                    continue;
                                unicastGoalState.getGoalStateBuilder().putNeighborStates(neighbor.getConfiguration().getId(), neighbor);
                                for (Neighbor.NeighborConfiguration.FixedIp fixIp : neighbor.getConfiguration().getFixedIpsList())
                                {
                                    if (ips.contains(fixIp.getIpAddress()))
                                    {
                                        subnetService.buildSubnetState(fixIp.getSubnetId(), unicastGoalState, multicastGoalState);
                                    }
                                }
                            }
                        }

                        routerService.buildRouterState(routerInfo, subnetRoutingTable, unicastGoalState, multicastGoalState);
                        subnetService.buildSubnetState(subnetId, unicastGoalState, multicastGoalState);
                    }
                }
            }
        }

        if (unicastGoalStateMap.size() == 0) {
            //throw new RouterInfoInvalid();
            return new ArrayList<>();
        }

        List<UnicastGoalStateV2> unicastGoalStates = unicastGoalStateMap.values()
                .stream().peek(gs -> {
                    gs.setGoalState(gs.getGoalStateBuilder().build());
                    gs.setGoalStateBuilder(null);
                }).collect(Collectors.toList());

        //TODO: Merge UnicastGoalState with the same content, build MulticastGoalState

        return grpcDataPlaneClient.sendGoalStates(unicastGoalStates);
    }

    private InternalDPMResultList buildResult(NetworkConfiguration networkConfig, List<String> failedHosts, long startTime) {
        InternalDPMResultList result = new InternalDPMResultList();
        long endTime = System.currentTimeMillis();
        result.setOverrallTime(endTime - startTime);

        String resultMessage;
        if (failedHosts == null || failedHosts.size() == 0) {
            resultMessage = "Successfully Handle request !!";
        } else {
            resultMessage = "Failed Handle request !!";
        }

        InternalDPMResult internalDPMResult = new InternalDPMResult();
        internalDPMResult.setElapseTime(endTime - startTime);
        internalDPMResult.setFailedHosts(failedHosts);
        //internalDPMResult.setResourceId(networkConfig.getRsType());
        internalDPMResult.setResourceType(networkConfig.getRsType());
        internalDPMResult.setStatus(resultMessage);

        result.setResultMessage(resultMessage);
        result.setResultList(Collections.singletonList(internalDPMResult));

        return result;
    }

    private InternalDPMResultList processNetworkConfiguration(NetworkConfiguration networkConfig) throws Exception {
        long startTime = System.currentTimeMillis();
        List<String> failedHosts = new ArrayList<>();

        switch(networkConfig.getRsType()) {
            case PORT:
                failedHosts = processPortConfiguration(networkConfig);
                break;
            case NEIGHBOR:
                failedHosts = processNeighborConfiguration(networkConfig);
                break;
            case SECURITYGROUP:
                failedHosts = processSecurityGroupConfiguration(networkConfig);
                break;
            case ROUTER:
                failedHosts = processRouterConfiguration(networkConfig);
                break;
            default:
                throw new UnknownResourceType();
        }
        return buildResult(networkConfig, failedHosts, startTime);
    }

    @Override
    public InternalDPMResultList createNetworkConfiguration(NetworkConfiguration networkConfig) throws Exception {
        return processNetworkConfiguration(networkConfig);
    }

    @Override
    public InternalDPMResultList updateNetworkConfiguration(NetworkConfiguration networkConfig) throws Exception {
        return processNetworkConfiguration(networkConfig);
    }

    @Override
    public InternalDPMResultList deleteNetworkConfiguration(NetworkConfiguration networkConfig) throws Exception {
        return processNetworkConfiguration(networkConfig);
    }
}
