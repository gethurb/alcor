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


/*
This is the code for the test controller, for testing the reactions between the Network Configuration manager and
the ACA.

Params:
1. Number of ports to generate on aca node one
2. Number of ports to generate on aca node two
3. IP of aca_node_one
4. IP of aca_node_two
5. IP of the GRPC call
6. Port of the GRPC call
7. User name of aca_nodes
8. Password of aca_nodes
9. Ping mode, either CONCURRENT_PING_MODE(0 and default), or SEQUENTIAL_PING_MODE(other numnbers)
10. Whether execute background ping or not. If set to 1, execute background ping; otherwise, don't execute background ping
11. Whether to create containers and execute ping.
*/
package com.futurewei.alcor.pseudo_controller;

import com.futurewei.alcor.schema.*;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;


public class pseudo_controller {

    static String aca_node_one_ip = "ip_one";
    static String aca_node_two_ip = "ip_two";
    static final int NUMBER_OF_NODES = 2;
    static String ncm_ip = "ip_three";
    static int ncm_port = 123;
    static String user_name = "root";
    static String password = "abcdefg";
    static int ports_to_generate_on_aca_node_one = 1;
    static int ports_to_generate_on_aca_node_two = 1;
    static String docker_ps_cmd = "docker ps";
    static String vpc_id_1 = "2b08a5bc-b718-11ea-b3de-111111111112";
    static String port_ip_template = "11111111-b718-11ea-b3de-";
    static String subnet_id_1 = "27330ae4-b718-11ea-b3df-111111111113";
    static String subnet_id_2 = "27330ae4-b718-11ea-b3df-111111111114";
    static String ips_ports_ip_prefix = "10";
    static String mac_port_prefix = "6c:dd:ee:";
    static String project_id = "alcor_testing_project";
    static SortedMap<String, String> ip_mac_map = new TreeMap<>();
    static Vector<String> aca_node_one_commands = new Vector<>();
    static Vector<String> aca_node_two_commands = new Vector<>();
    static SortedMap<String, String> port_ip_to_host_ip_map = new TreeMap<>();
    static SortedMap<String, String> port_ip_to_id_map = new TreeMap<>();
    static SortedMap<String, String> port_ip_to_container_name = new TreeMap<>();
    static Vector<String> node_one_port_ips = new Vector<>();
    static Vector<String> node_two_port_ips = new Vector<>();
    static final int CONCURRENT_PING_MODE = 0;
    static int user_chosen_ping_method = CONCURRENT_PING_MODE;
    static final int THREAD_POOL_SIZE = 10;
    static ExecutorService concurrent_create_containers_thread_pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    static ExecutorService backgroundPingExecutor = Executors.newFixedThreadPool(1);
    static int user_chosen_execute_background_ping = 0;
    static final int DO_EXECUTE_BACKGROUND_PING = 1;
    static int finished_sending_goalstate_hosts_count = 0;
    static final int CREATE_CONTAINER_AND_PING = 0;
    static int whether_to_create_containers_and_ping = CREATE_CONTAINER_AND_PING;
    static final int DEFAULT_VLAN_ID = 1;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Start of the test controller");
        if (args.length == 11) {
            System.out.println("User passed in params and we need to read them.");
            ports_to_generate_on_aca_node_one = Integer.parseInt(args[0]);
            ports_to_generate_on_aca_node_two = Integer.parseInt(args[1]);
            aca_node_one_ip = args[2];
            aca_node_two_ip = args[3];
            ncm_ip = args[4];
            ncm_port = Integer.parseInt(args[5]);
            user_name = args[6];
            password = args[7];
            user_chosen_ping_method = Integer.parseInt(args[8]);
            user_chosen_execute_background_ping = Integer.parseInt(args[9]);
            whether_to_create_containers_and_ping = Integer.parseInt(args[10]);
        }
        System.out.println("ACA node one has "+ ports_to_generate_on_aca_node_one + " ports;\nACA node two has "+ports_to_generate_on_aca_node_two+" ports. \nTotal ports: "+(ports_to_generate_on_aca_node_one + ports_to_generate_on_aca_node_two));
        generate_ip_macs(ports_to_generate_on_aca_node_one + ports_to_generate_on_aca_node_two);
        create_containers_on_both_hosts_concurrently();
        System.out.println("aca_node_one_ip: " + aca_node_one_ip + "\naca_node_two_ip: " + aca_node_two_ip + "\nuser name: " + user_name + "\npassword: " + password);

        System.out.println("Containers setup done, now we gotta construct the GoalStateV2");

        System.out.println("Trying to build the GoalStateV2 for " + ports_to_generate_on_aca_node_one + ports_to_generate_on_aca_node_two + " Ports");


        Goalstate.GoalStateV2.Builder GoalState_builder_one = Goalstate.GoalStateV2.newBuilder();
        Goalstate.GoalStateV2.Builder GoalState_builder_two = Goalstate.GoalStateV2.newBuilder();
        Goalstate.HostResources.Builder host_resource_builder_node_one = Goalstate.HostResources.newBuilder();
        Goalstate.HostResources.Builder host_resource_builder_node_two = Goalstate.HostResources.newBuilder();
        Goalstate.HostResources.Builder host_resource_builder_node_one_port_one_neighbor = Goalstate.HostResources.newBuilder();

        for (String port_ip : ip_mac_map.keySet()) {
            String host_ip = port_ip_to_host_ip_map.get(port_ip);
            String port_id = port_ip_to_id_map.get(port_ip);
            String port_mac = ip_mac_map.get(port_ip);
            // if it's on node 1, we don't add neighbor info here,
            // start of setting up port 1 on aca node 1
            Port.PortState.Builder new_port_states = Port.PortState.newBuilder();

            new_port_states.setOperationType(Common.OperationType.CREATE);

            // fill in port state structs for port 1
            Port.PortConfiguration.Builder config = new_port_states.getConfigurationBuilder();
            config.
                    setRevisionNumber(2).
                    setUpdateType(Common.UpdateType.FULL).
                    setId(port_id).
                    setVpcId(vpc_id_1).
                    setName(("tap" + port_id).substring(0, 14)).
                    setAdminStateUp(true).
                    setMacAddress(port_mac);
            Port.PortConfiguration.FixedIp.Builder fixedIpBuilder = Port.PortConfiguration.FixedIp.newBuilder();
            fixedIpBuilder.setSubnetId(subnet_id_1);
            fixedIpBuilder.setIpAddress(port_ip);
            config.addFixedIps(fixedIpBuilder.build());
            Port.PortConfiguration.SecurityGroupId securityGroupId = Port.PortConfiguration.SecurityGroupId.newBuilder().setId("2").build();
            config.addSecurityGroupIds(securityGroupId);

            new_port_states.setConfiguration(config.build());

            Port.PortState port_state_one = new_port_states.build();
            Goalstate.ResourceIdType.Builder port_one_resource_Id_builder = Goalstate.ResourceIdType.newBuilder();
            port_one_resource_Id_builder.setType(Common.ResourceType.PORT).setId(port_state_one.getConfiguration().getId());
            Goalstate.ResourceIdType port_one_resource_id = port_one_resource_Id_builder.build();

            // add a new neighbor state with CREATE
            Neighbor.NeighborState.Builder new_neighborState_builder = Neighbor.NeighborState.newBuilder();
            new_neighborState_builder.setOperationType(Common.OperationType.CREATE);

            // fill in neighbor state structs of port 3
            Neighbor.NeighborConfiguration.Builder NeighborConfiguration_builder = Neighbor.NeighborConfiguration.newBuilder();
            NeighborConfiguration_builder.setRevisionNumber(2);
            NeighborConfiguration_builder.setVpcId(vpc_id_1);
            NeighborConfiguration_builder.setId(port_id + "_n");
            NeighborConfiguration_builder.setMacAddress(port_mac);
            NeighborConfiguration_builder.setHostIpAddress(host_ip);

            Neighbor.NeighborConfiguration.FixedIp.Builder neighbor_fixed_ip_builder = Neighbor.NeighborConfiguration.FixedIp.newBuilder();
            neighbor_fixed_ip_builder.setNeighborType(Neighbor.NeighborType.L2);
            neighbor_fixed_ip_builder.setSubnetId(subnet_id_1);
            neighbor_fixed_ip_builder.setIpAddress(port_ip);

            NeighborConfiguration_builder.addFixedIps(neighbor_fixed_ip_builder.build());

            new_neighborState_builder.setConfiguration(NeighborConfiguration_builder.build());
            Neighbor.NeighborState neighborState_node_one = new_neighborState_builder.build();

            if (host_ip.equals(aca_node_one_ip)) {

                GoalState_builder_one.putPortStates(port_state_one.getConfiguration().getId(), port_state_one);

                host_resource_builder_node_one.addResources(port_one_resource_id);
                // if this port is on host_one, then it is a neighbor for ports on host_two

                GoalState_builder_two.putNeighborStates(neighborState_node_one.getConfiguration().getId(), neighborState_node_one);
                Goalstate.ResourceIdType resource_id_type_neighbor_node_one = Goalstate.ResourceIdType.newBuilder().
                        setType(Common.ResourceType.NEIGHBOR).setId(neighborState_node_one.getConfiguration().getId()).build();
                host_resource_builder_node_two.addResources(resource_id_type_neighbor_node_one);
            } else {
                GoalState_builder_two.putPortStates(port_state_one.getConfiguration().getId(), port_state_one);

                host_resource_builder_node_two.addResources(port_one_resource_id);
                // if this port is on host_two, then it is a neighbor for ports on host_one
                GoalState_builder_two.putNeighborStates(neighborState_node_one.getConfiguration().getId(), neighborState_node_one);
                Goalstate.ResourceIdType resource_id_type_neighbor_node_one = Goalstate.ResourceIdType.newBuilder().
                        setType(Common.ResourceType.NEIGHBOR).setId(neighborState_node_one.getConfiguration().getId()).build();
                host_resource_builder_node_one_port_one_neighbor.addResources(resource_id_type_neighbor_node_one);
            }
            System.out.println("Finished port state for port [" + port_ip + "] on host: [" + host_ip + "]");
        }
//        Router.RouterState.Builder router_state_builder = Router.RouterState.newBuilder();
//
//        Router.RouterConfiguration.Builder router_configuration_builder = Router.RouterConfiguration.newBuilder();
//
//        Router.RouterConfiguration.RoutingRule.Builder router_rule_builder = Router.RouterConfiguration.RoutingRule.newBuilder();
//
//        Router.RouterConfiguration.RoutingRuleExtraInfo.Builder routing_rule_extra_info_builder = Router.RouterConfiguration.RoutingRuleExtraInfo.newBuilder();
//
//        routing_rule_extra_info_builder
//                .setDestinationType(Router.DestinationType.VPC_GW)
//                .setNextHopMac("6c:dd:ee:0:0:40");
//
//        router_rule_builder
//                .setId("tc_sample_routing_rule")
//                .setName("tc_sample_routing_rule")
//                .setDestination("10.0.0.0/24")
//                .setNextHopIp(aca_node_one_ip)
//                .setPriority(999)
//                .setRoutingRuleExtraInfo(routing_rule_extra_info_builder.build());
//
//        Router.RouterConfiguration.SubnetRoutingTable.Builder subnet_routing_table_builder = Router.RouterConfiguration.SubnetRoutingTable.newBuilder();
//        subnet_routing_table_builder
//                .setSubnetId(subnet_id_1)
//                .addRoutingRules(router_rule_builder.build());
//
//        Router.RouterConfiguration.SubnetRoutingTable.Builder subnet_routing_table_builder_two = Router.RouterConfiguration.SubnetRoutingTable.newBuilder();
//        subnet_routing_table_builder_two
//                .setSubnetId(subnet_id_2)
//                .addRoutingRules(router_rule_builder.build());
//
//        router_configuration_builder
//                .setRevisionNumber(777)
//                .setRequestId("tc_sample_routing_rule"+"_rs")
//                .setId("tc_sample_routing_rule"+"_r")
//                .setUpdateType(Common.UpdateType.FULL)
//                .setHostDvrMacAddress("6c:dd:ee:0:0:40")
//                .addSubnetRoutingTables(subnet_routing_table_builder.build())
//                .addSubnetRoutingTables(subnet_routing_table_builder_two.build());
//
//        router_state_builder
//                .setOperationType(Common.OperationType.INFO)
//                .setConfiguration(router_configuration_builder.build());
//        Router.RouterState router_state = router_state_builder.build();
//
//        GoalState_builder_two.putRouterStates(router_state.getConfiguration().getId(), router_state);
//        GoalState_builder_one.putRouterStates(router_state.getConfiguration().getId(), router_state);
//        Goalstate.ResourceIdType resource_id_type_router_node_two = Goalstate.ResourceIdType.newBuilder().
//                setType(Common.ResourceType.ROUTER)
//                .setId(router_state.getConfiguration().getId())
//                .build();
//        host_resource_builder_node_two.addResources(resource_id_type_router_node_two);
//        host_resource_builder_node_one.addResources(resource_id_type_router_node_two);
        // fill in subnet state structs
        Subnet.SubnetState.Builder new_subnet_states = Subnet.SubnetState.newBuilder();

        new_subnet_states.setOperationType(Common.OperationType.INFO);

        Subnet.SubnetConfiguration.Builder subnet_configuration_builder = Subnet.SubnetConfiguration.newBuilder();

        subnet_configuration_builder.setRevisionNumber(2);
        subnet_configuration_builder.setVpcId(vpc_id_1);
        subnet_configuration_builder.setId(subnet_id_1);
        subnet_configuration_builder.setCidr("10.0.0.0/24");
        subnet_configuration_builder.setTunnelId(21);
        subnet_configuration_builder.setGateway(Subnet.SubnetConfiguration.Gateway.newBuilder().setIpAddress("0.0.0.0").setMacAddress("6c:dd:ee:0:0:40").build());

        new_subnet_states.setConfiguration(subnet_configuration_builder.build());

        Subnet.SubnetState subnet_state_for_both_nodes = new_subnet_states.build();

        // fill in subnet state structs
//        Subnet.SubnetState.Builder new_subnet_states_two = Subnet.SubnetState.newBuilder();
//
//        new_subnet_states_two.setOperationType(Common.OperationType.INFO);
//
//        Subnet.SubnetConfiguration.Builder subnet_configuration_builder_two = Subnet.SubnetConfiguration.newBuilder();
//
//        subnet_configuration_builder_two.setRevisionNumber(2);
//        subnet_configuration_builder_two.setVpcId(vpc_id_1);
//        subnet_configuration_builder_two.setId(subnet_id_2);
//        subnet_configuration_builder_two.setCidr("10.0.0.0/24");
//        subnet_configuration_builder_two.setTunnelId(22);
//        subnet_configuration_builder_two.setGateway(Subnet.SubnetConfiguration.Gateway.newBuilder().setIpAddress("0.0.0.1").setMacAddress("6c:dd:ee:0:0:41").build());
//
//        new_subnet_states_two.setConfiguration(subnet_configuration_builder_two.build());
//
//        Subnet.SubnetState subnet_state_for_both_nodes_two = new_subnet_states_two.build();

        // put the new subnet state of subnet 1 into the goalstatev2

        // fill in VPC state structs
        Vpc.VpcState.Builder new_vpc_states = Vpc.VpcState.newBuilder();
        new_vpc_states.setOperationType(Common.OperationType.INFO);

        Vpc.VpcConfiguration.Builder vpc_configuration_builder = Vpc.VpcConfiguration.newBuilder();
        vpc_configuration_builder.setCidr("10.0.0.0/16");
        vpc_configuration_builder.setId(vpc_id_1);
        vpc_configuration_builder.setName("test_vpc");
        vpc_configuration_builder.setTunnelId(21);
        vpc_configuration_builder.setProjectId(project_id);
        vpc_configuration_builder.setRevisionNumber(2);

        new_vpc_states.setConfiguration(vpc_configuration_builder.build());
        Vpc.VpcState vpc_state_for_both_nodes = new_vpc_states.build();

        GoalState_builder_one.putSubnetStates(subnet_state_for_both_nodes.getConfiguration().getId(), subnet_state_for_both_nodes);
//        GoalState_builder_one.putSubnetStates(subnet_state_for_both_nodes_two.getConfiguration().getId(), subnet_state_for_both_nodes_two);
        GoalState_builder_two.putSubnetStates(subnet_state_for_both_nodes.getConfiguration().getId(), subnet_state_for_both_nodes);
//        GoalState_builder_two.putSubnetStates(subnet_state_for_both_nodes_two.getConfiguration().getId(), subnet_state_for_both_nodes_two);
        GoalState_builder_one.putVpcStates(vpc_state_for_both_nodes.getConfiguration().getId(), vpc_state_for_both_nodes);
        GoalState_builder_two.putVpcStates(vpc_state_for_both_nodes.getConfiguration().getId(), vpc_state_for_both_nodes);

        Goalstate.ResourceIdType subnet_resource_id_type = Goalstate.ResourceIdType.newBuilder()
                .setType(Common.ResourceType.SUBNET).setId(subnet_state_for_both_nodes.getConfiguration().getId()).build();
//        Goalstate.ResourceIdType subnet_resource_id_type_two = Goalstate.ResourceIdType.newBuilder()
//                .setType(Common.ResourceType.SUBNET).setId(subnet_state_for_both_nodes_two.getConfiguration().getId()).build();

        Goalstate.ResourceIdType vpc_resource_id_type = Goalstate.ResourceIdType.newBuilder().setType(Common.ResourceType.VPC).setId(vpc_state_for_both_nodes.getConfiguration().getId()).build();
        host_resource_builder_node_one.addResources(subnet_resource_id_type);
        host_resource_builder_node_two.addResources(subnet_resource_id_type);
        host_resource_builder_node_one_port_one_neighbor.addResources(subnet_resource_id_type);
//        host_resource_builder_node_one.addResources(subnet_resource_id_type_two);
//        host_resource_builder_node_two.addResources(subnet_resource_id_type_two);
//        host_resource_builder_node_one_port_one_neighbor.addResources(subnet_resource_id_type_two);
        host_resource_builder_node_one.addResources(vpc_resource_id_type);
        host_resource_builder_node_two.addResources(vpc_resource_id_type);
        host_resource_builder_node_one_port_one_neighbor.addResources(vpc_resource_id_type);

        GoalState_builder_one.putHostResources(aca_node_one_ip, host_resource_builder_node_one.build());
        GoalState_builder_two.putHostResources(aca_node_two_ip, host_resource_builder_node_two.build());
        GoalState_builder_two.putHostResources(aca_node_one_ip, host_resource_builder_node_one_port_one_neighbor.build());
        Goalstate.GoalStateV2 message_one = GoalState_builder_one.build();
        Goalstate.GoalStateV2 message_two = GoalState_builder_two.build();

//        System.out.println("Built GoalState successfully, GoalStateV2 content for PORT1: \n" + message_one.toString() + "\n");
//        System.out.println("Built GoalState successfully, GoalStateV2 content for PORT2: \n" + message_two.toString() + "\n");
        System.out.println("GoalStateV2 size in bytes for host1: \n" + message_one.getSerializedSize() + "\n");
        System.out.println("GoalStateV2 size in bytes for host2: \n" + message_two.getSerializedSize() + "\n");

        System.out.println("Time to call the GRPC functions");

        ManagedChannel channel = ManagedChannelBuilder.forAddress(ncm_ip, ncm_port).usePlaintext().build();
        System.out.println("Constructed channel");
        GoalStateProvisionerGrpc.GoalStateProvisionerStub stub = GoalStateProvisionerGrpc.newStub(channel);

        System.out.println("Created stub");
        StreamObserver<Goalstateprovisioner.GoalStateOperationReply> message_observer = new StreamObserver<>() {
            @Override
            public void onNext(Goalstateprovisioner.GoalStateOperationReply value) {
                finished_sending_goalstate_hosts_count ++ ;
                System.out.println("onNext function with this GoalStateOperationReply: \n" + value.toString() + "\n");
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("onError function with this GoalStateOperationReply: \n" + t.getMessage() + "\n");
            }

            @Override
            public void onCompleted() {
                System.out.println("onCompleted");
            }
        };
        System.out.println("Created GoalStateOperationReply observer class");
        io.grpc.stub.StreamObserver<Goalstate.GoalStateV2> response_observer = stub.pushGoalStatesStream(message_observer);
        System.out.println("Connected the observers");
        response_observer.onNext(message_one);
        response_observer.onNext(message_two);

        System.out.println("After calling onNext");
        response_observer.onCompleted();

        System.out.println("Wait no longer than 6000 seconds until both goalstates are sent to both hosts.");
        Awaitility.await().atMost(6000, TimeUnit.SECONDS).until(()-> finished_sending_goalstate_hosts_count == NUMBER_OF_NODES);


//        System.out.println("Try to send gsv1 to the host!");
//
//        ManagedChannel v1_chan_aca_1 = ManagedChannelBuilder.forAddress(aca_node_one_ip, 50001).usePlaintext().build();
//        ManagedChannel v1_chan_aca_2 = ManagedChannelBuilder.forAddress(aca_node_two_ip, 50001).usePlaintext().build();
//
//        GoalStateProvisionerGrpc.GoalStateProvisionerBlockingStub v1_stub_aca_1 = GoalStateProvisionerGrpc.newBlockingStub(v1_chan_aca_1);
//        GoalStateProvisionerGrpc.GoalStateProvisionerBlockingStub v1_stub_aca_2 = GoalStateProvisionerGrpc.newBlockingStub(v1_chan_aca_2);
//
//
//        //  try to send gsv1 to the host, to see if the server supports gsv1 or not.
//        for(int i = 0 ; i < ports_to_generate_on_each_aca_node ; i ++){
//            System.out.println("Sending the " + i + "th gsv1 to ACA1 at: "+aca_node_one_ip);
//            Goalstateprovisioner.GoalStateOperationReply reply_v1_aca_1 = v1_stub_aca_1.pushNetworkResourceStates(Goalstate.GoalState.getDefaultInstance());
//            System.out.println("Received the " + i + "th reply: " + reply_v1_aca_1.toString()+" from ACA1 at: "+aca_node_one_ip);
//            System.out.println("Sending the " + i + "th gsv1 to ACA2 at: "+aca_node_two_ip);
//            Goalstateprovisioner.GoalStateOperationReply reply_v1_aca_2 = v1_stub_aca_2.pushNetworkResourceStates(Goalstate.GoalState.getDefaultInstance());
//            System.out.println("Received the " + i + "th reply: " + reply_v1_aca_2.toString()+" from ACA1 at: "+aca_node_two_ip);
//        }
//        System.out.println("Done sending gsv1 to the host!");

        System.out.println("After the GRPC call, it's time to do the ping test");

        System.out.println("Sleep 10 seconds before executing the ping");
        try {
            TimeUnit.SECONDS.sleep(10);

        } catch (Exception e) {
            System.out.println("I can't sleep!!!!");

        }
        List<concurrent_run_cmd> concurrent_ping_cmds = new ArrayList<>();

        for (int i = 0; i < node_two_port_ips.size(); i++) {
            String pinger_ip = node_one_port_ips.get(i % node_one_port_ips.size());
            String pinger_container_name = port_ip_to_container_name.get(pinger_ip);
//            String pingee_ip = node_two_port_ips.get(i);
            String pingee_ip = node_two_port_ips.get(i);
            String ping_cmd = "docker exec " + pinger_container_name + " ping -I " + pinger_ip + " -c1 " + pingee_ip;
            concurrent_ping_cmds.add(new concurrent_run_cmd(ping_cmd, aca_node_one_ip, user_name, password));
            System.out.println("Ping command is added: [" + ping_cmd + "]");
        }

        System.out.println("Time to execute these ping commands concurrently");

        if(whether_to_create_containers_and_ping == CREATE_CONTAINER_AND_PING){
            // Execute the pings.
            for (concurrent_run_cmd cmd : concurrent_ping_cmds) {
                if (user_chosen_ping_method == CONCURRENT_PING_MODE) {
                    //concurrent
                    Thread t = new Thread(cmd);
                    t.start();
                } else {
                    // sequential
                    cmd.run();
                }
            }
        }

        System.out.println("End of the test controller");
        channel.shutdown();
        try {
            TimeUnit.SECONDS.sleep(10);

        } catch (Exception e) {
            System.out.println("I can't sleep!!!!");

        }
        System.exit(0);
    }

    private static void create_containers_on_both_hosts() {
        System.out.println("Creating containers on both hosts");
        int i = 1;
        for (String port_ip : ip_mac_map.keySet()) {
            String port_mac = ip_mac_map.get(port_ip);
            String container_name = "test" + Integer.toString(i);
            port_ip_to_container_name.put(port_ip, container_name);
            String create_container_cmd = "docker run -itd --name " + container_name + " --net=none --label test=ACA busybox sh";
            String ovs_docker_add_port_cmd = "ovs-docker add-port br-int eth0 " + container_name + " --ipaddress=" + port_ip + "/16 --macaddress=" + port_mac;
            String ovs_set_vlan_cmd = "ovs-docker set-vlan br-int eth0 " + container_name + " " + String.valueOf(DEFAULT_VLAN_ID);

            int ip_last_octet = Integer.parseInt(port_ip.split("\\.")[3]);
            if (ip_last_octet % 2 != 0) {
                System.out.println("i = " + i + " , assigning IP: [" + port_ip + "] to node: [" + aca_node_one_ip + "]");
                node_one_port_ips.add(port_ip);
                aca_node_one_commands.add(create_container_cmd);
                aca_node_one_commands.add(ovs_docker_add_port_cmd);
                aca_node_one_commands.add(ovs_set_vlan_cmd);
                port_ip_to_host_ip_map.put(port_ip, aca_node_one_ip);
            } else {
                System.out.println("i = " + i + " , assigning IP: [" + port_ip + "] to node: [" + aca_node_two_ip + "]");
                node_two_port_ips.add(port_ip);
                aca_node_two_commands.add(create_container_cmd);
                aca_node_two_commands.add(ovs_docker_add_port_cmd);
                aca_node_two_commands.add(ovs_set_vlan_cmd);
                port_ip_to_host_ip_map.put(port_ip, aca_node_two_ip);
            }
            i++;
        }
        aca_node_one_commands.add(docker_ps_cmd);
        aca_node_two_commands.add(docker_ps_cmd);

        execute_ssh_commands(aca_node_one_commands, aca_node_one_ip, user_name, password);
        execute_ssh_commands(aca_node_two_commands, aca_node_two_ip, user_name, password);

        System.out.println("DONE creating containers on both hosts");

    }

    private static void create_containers_on_both_hosts_concurrently() {
        System.out.println("Creating containers on both hosts, ip_mac_map has " + ip_mac_map.keySet().size() + "keys");
        int i = 1;
        String background_pinger = "";
        String background_pingee = "";
        // use a countdown latch to wait for the threads to finish.
        CountDownLatch latch = new CountDownLatch(ip_mac_map.keySet().size());
        for (String port_ip : ip_mac_map.keySet()) {
            String port_mac = ip_mac_map.get(port_ip);
            String container_name = "test" + Integer.toString(i);
            port_ip_to_container_name.put(port_ip, container_name);
            String create_container_cmd = "docker run -itd --name " + container_name + " --net=none --label test=ACA busybox sh";
            String ovs_docker_add_port_cmd = "ovs-docker add-port br-int eth0 " + container_name + " --ipaddress=" + port_ip + "/16 --macaddress=" + port_mac;
            String ovs_set_vlan_cmd = "ovs-docker set-vlan br-int eth0 " + container_name + " 1";
            Vector<String> create_one_container_and_assign_IP_vlax_commands = new Vector<>();
            create_one_container_and_assign_IP_vlax_commands.add(create_container_cmd);
            create_one_container_and_assign_IP_vlax_commands.add(ovs_docker_add_port_cmd);
            create_one_container_and_assign_IP_vlax_commands.add(ovs_set_vlan_cmd);

//            int ip_last_octet = Integer.parseInt(port_ip.split("\\.")[3]);
            if (node_one_port_ips.size() != ports_to_generate_on_aca_node_one) {
                System.out.println("i = " + i + " , assigning IP: [" + port_ip + "] to node: [" + aca_node_one_ip + "]");
                node_one_port_ips.add(port_ip);
                port_ip_to_host_ip_map.put(port_ip, aca_node_one_ip);
                if(whether_to_create_containers_and_ping == CREATE_CONTAINER_AND_PING){
                    concurrent_create_containers_thread_pool.execute(() -> {
                        execute_ssh_commands(create_one_container_and_assign_IP_vlax_commands, aca_node_one_ip, user_name, password);
                        latch.countDown();
                    });
                }
                background_pinger = port_ip;
            } else {
                System.out.println("i = " + i + " , assigning IP: [" + port_ip + "] to node: [" + aca_node_two_ip + "]");
                node_two_port_ips.add(port_ip);
                port_ip_to_host_ip_map.put(port_ip, aca_node_two_ip);
                if(whether_to_create_containers_and_ping == CREATE_CONTAINER_AND_PING){
                    concurrent_create_containers_thread_pool.execute(() -> {
                        execute_ssh_commands(create_one_container_and_assign_IP_vlax_commands, aca_node_two_ip, user_name, password);
                        latch.countDown();
                    });
                }
                background_pingee = port_ip;
            }
            i++;
        }

        if(whether_to_create_containers_and_ping == CREATE_CONTAINER_AND_PING){
            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (user_chosen_execute_background_ping == DO_EXECUTE_BACKGROUND_PING) {
                // start the background thread here doing the ping from 1 port to another, util the ping is successful.
                // it pings every 0.001 second, or 1 millisecond, for 60 seconds
                String background_ping_command = "docker exec " + port_ip_to_container_name.get(background_pinger) + " ping -I " + background_pinger + " -c 60000 -i  0.001 " + background_pingee + " > /home/user/background_ping_output.log";
                System.out.println("Created background ping cmd: " + background_ping_command);
                concurrent_run_cmd c = new concurrent_run_cmd(background_ping_command, aca_node_one_ip, user_name, password);
                backgroundPingExecutor.execute(c);
            }
        }

        System.out.println("DONE creating containers on both hosts, host 1 has "+node_one_port_ips.size()+" ports, host 2 has "+node_two_port_ips.size()+" ports");
    }


    // Generates IP/MAC for host_many_per_host, and inserts them into the hashmap
    public static void generate_ip_macs(int amount_of_ports_to_generate) {
        System.out.println("Need to generate " + amount_of_ports_to_generate + " ports");
        int i = 2;
        while (ip_mac_map.size() != amount_of_ports_to_generate) {
            if (i % 100 != 0) {
                String ip_2nd_octet = Integer.toString(i / 10000);
                String ip_3nd_octet = Integer.toString((i % 10000) / 100);
                String ip_4nd_octet = Integer.toString(i % 100);
                String ip_for_port = ips_ports_ip_prefix + "." + ip_2nd_octet + "." + ip_3nd_octet + "." + ip_4nd_octet;
                String mac_for_port = mac_port_prefix + ip_2nd_octet + ":" + ip_3nd_octet + ":" + ip_4nd_octet;
                String id_for_port = port_ip_template + ips_ports_ip_prefix + String.format("%03d", (i / 10000)) + String.format("%03d", ((i % 10000) / 100)) + String.format("%03d", (i % 100));
                System.out.println("Generated Port " + i + " with IP: [" + ip_for_port + "], ID :[ " + id_for_port + "] and MAC: [" + mac_for_port + "]");
                ip_mac_map.put(ip_for_port, mac_for_port);
                port_ip_to_id_map.put(ip_for_port, id_for_port);
            }
            i++;
        }
        System.out.println("Finished generating " + amount_of_ports_to_generate + " ports, ip->mac map has "+ ip_mac_map.size() +" entries, ip->id map has "+port_ip_to_id_map.size()+" entries");
    }


    public static void execute_ssh_commands(Vector<String> commands, String host_ip, String host_user_name, String host_password) {
        try {
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            JSch jsch = new JSch();
            Session session = jsch.getSession(host_user_name, host_ip, 22);
            session.setPassword(host_password);
            session.setConfig(config);
            session.connect();
            System.out.println("Connected");
            for (int j = 0; j < commands.size(); j++) {
                String command = commands.get(j);
                System.out.println("Start of executing command [" + command + "] on host: " + host_ip);
                Channel channel = session.openChannel("exec");
                ((ChannelExec) channel).setCommand(command);
                channel.setInputStream(null);
                ((ChannelExec) channel).setErrStream(System.err);

                InputStream in = channel.getInputStream();
                channel.connect();
                byte[] tmp = new byte[1024];
                while (true) {
                    while (in.available() > 0) {
                        int i = in.read(tmp, 0, 1024);
                        if (i < 0) break;
                        System.out.print(new String(tmp, 0, i));
                    }
                    if (channel.isClosed()) {
                        System.out.println("exit-status: " + channel.getExitStatus());
                        break;
                    }
                }
                System.out.println("End of executing command [" + command + "] on host: " + host_ip);
                channel.disconnect();
            }

            session.disconnect();
            System.out.println("DONE");
        } catch (Exception e) {
            System.err.println("Got this error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void executeBashCommand(String command) {
//        boolean success = false;
        System.out.println("Executing BASH command:\n   " + command);
        Runtime r = Runtime.getRuntime();
        // Use bash -c so we can handle things like multi commands separated by ; and
        // things like quotes, $, |, and \. My tests show that command comes as
        // one argument to bash, so we do not need to quote it to make it one thing.
        // Also, exec may object if it does not have an executable file as the first thing,
        // so having bash here makes it happy provided bash is installed and in path.
        String[] commands = {"bash", "-x", "-c", command};
        try {
            Process p = r.exec(commands);

            p.waitFor();
            BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = "";

            while ((line = b.readLine()) != null) {
                System.out.println(line);
            }

            b.close();
//            success = true;
        } catch (Exception e) {
            System.err.println("Failed to execute bash with command: " + command);
            e.printStackTrace();
        }
        //        return success;
    }
}

class concurrent_run_cmd implements Runnable {
    String command_to_run, host, user_name, password;

    @Override
    public void run() {
//        Vector<String> cmd_list = new Vector<>();
        System.out.println("Need to execute this command concurrently: [" + this.command_to_run + "]");
//        cmd_list.add(this.command_to_run);
//        pseudo_controller.execute_ssh_commands(cmd_list, host, user_name, password);
        pseudo_controller.executeBashCommand(command_to_run);
    }

    public concurrent_run_cmd(String cmd, String host, String user_name, String password) {
        this.command_to_run = cmd;
        this.host = host;
        this.user_name = user_name;
        this.password = password;
    }

}