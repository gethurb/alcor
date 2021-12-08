package com.futurewei.alcor.netwconfigmanager.util;

import com.futurewei.alcor.netwconfigmanager.entity.HostGoalState;
import com.futurewei.alcor.netwconfigmanager.exception.UnexpectedHostNumException;
import com.futurewei.alcor.schema.*;
import com.futurewei.alcor.web.entity.port.PortEntity;
import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VmTopologyConstruct {

    public static int vmNum = 1_000_000;
    public static Graph vmTopology;

    private class Graph {
        //保存边的类
        private static class Edge {
            int to;                                //到那个点
            int w;                                //权值
            int next;                            //下一条边在 edge数组中的索引

            public Edge(int to, int w, int next) {        //构造方法
                super();
                this.to = to;
                this.w = w;
                this.next = next;
            }
        }

        private int edgeIndex = 1;                //edge数组的下一个空位的下标，注意得从1开始
        private int[] head = new int[100];
        private Edge[] edgeArr = new Edge[100];        //存储边的数组

        //添加边的方法
        public void add(int u, int v, int w) {
            edgeArr[edgeIndex] = new Edge(v, w, head[u]);
            head[u] = edgeIndex;//head数组里面保存的是第一个边在edge数组中的索引索引
            edgeIndex++;//指向空节点
        }

        //通过顶点索引(index)获取其所有边
        public void getAllEdge() {
            for (int i = head[edgeIndex]; i != 0; i = edgeArr[i].next) {        //遍历获得所有边在edgeArr中的索引
                Edge edge = edgeArr[i];        //获取一条边
            }
        }

        public void addBroadcastEdge(int v,int w){         //使得v指向其他所有其他节点
            for (int i=0;i<head.length;i++){
                if(v!=i){
                    add(v,i,w);
                }
            }
        }

        public void delete(int u,int v){
            int last=0;
            for (int i = head[u]; i >0; i=edgeArr[i].next) {
                Edge edge = edgeArr[i];
                if(edge.to==v){
                    if(i==head[u]){
                        head[u]=edge.next;
                    }else {
                        edgeArr[last].next=edgeArr[i].next;
                    }
                }
                last=i;
            }
        }

        public void deleteEdgeFrom(int v){                     // 删除顶点为v的所有出边

        }

        public void deleteEdgeTo(int v){                     // 删除顶点为v的所有入边

        }
    }

    private static Map<String, List<String>> subnetToVms;
    private static Map<String,String> vmToSubnet;
    private static Map<String,String> vmToAvailableZone;
    private static Map<String,String> subnetToAvailableZone;
    private static Map<String,List<String>> vmToSecurityGroups;
    private static Map<String,List<String>> securityGroupToVms;
    private static FeatureVector[][] FeatureVectors;

    private class FeatureVector {
        int intersectionSize;
        boolean isSameAZ;
        boolean isSameSubnet;
        int createTimeDistance;
        int[] distanceToGateways;
    }

    private static String convertIpToId(String ip){
        return "1";
    }

    private static String convertIdToIp(String id){
        return "1";
    }

    public static void updateTopology(Map<String, HostGoalState> hostGoalStates) throws UnexpectedHostNumException {
        for (Map.Entry<String, HostGoalState> entry : hostGoalStates.entrySet()) {
            String hostId = entry.getKey();
            HostGoalState hostGoalState = entry.getValue();

            if (hostGoalState.getGoalState().getHostResourcesMap().size() != 1) throw new UnexpectedHostNumException();
            boolean filter = true;

            for (Goalstate.HostResources resources : hostGoalState.getGoalState().getHostResourcesMap().values()) {
                for (Goalstate.ResourceIdType resource : resources.getResourcesList()) {
                    String resourceId = resource.getId();
                    Goalstate.GoalStateV2 goalState = hostGoalState.getGoalState();
                    if (resource.getType() == Common.ResourceType.PORT) {
                        List<String> portIds = new ArrayList<>();
                        Port.PortState portState = goalState.getPortStatesMap().get(resourceId);
                        Port.PortConfiguration configuration = portState.getConfiguration();
                        for (Port.PortConfiguration.FixedIp fixedIp : configuration.getFixedIpsList()) {
                            String portId = VmTopologyConstruct.convertIpToId(fixedIp.getIpAddress());
                            portIds.add(portId);
                            String subnetId = fixedIp.getSubnetId();
                            vmTopology.addBroadcastEdge(Integer.parseInt(portId), 1);
                            for (int i = 0; i < vmTopology.head.length; i++) {
                                if (vmTopology.head[i] != 0) {
                                    if (subnetId == vmToSubnet.get(i)) {
                                        FeatureVectors[Integer.parseInt(portId)][i].isSameSubnet = true;
                                    }
                                    if (subnetToAvailableZone.get(subnetId) == subnetToAvailableZone.get(i)) {
                                        FeatureVectors[Integer.parseInt(portId)][i].isSameAZ = true;
                                    }
                                }
                            }
                        }
                        for (Port.PortConfiguration.SecurityGroupId securityGroupId : configuration.getSecurityGroupIdsList()) {
                            String id = securityGroupId.getId();
                            securityGroupToVms.get(id).addAll(portIds);
                        }
                    }else if(resource.getType()== Common.ResourceType.ROUTER){


                    }else if(resource.getType()== Common.ResourceType.SUBNET){
                        Subnet.SubnetState subnetState=goalState.getSubnetStatesMap().get(resourceId);
                        Subnet.SubnetConfiguration configuration=subnetState.getConfiguration();
                        subnetToAvailableZone.put(configuration.getId(),configuration.getAvailabilityZone());
                    }else if(resource.getType()== Common.ResourceType.SECURITYGROUP){
                        SecurityGroup.SecurityGroupState securityGroupState=goalState.getSecurityGroupStatesMap().get(resourceId);
                        SecurityGroup.SecurityGroupConfiguration configuration=securityGroupState.getConfiguration();
                        for(SecurityGroup.SecurityGroupConfiguration.SecurityGroupRule securityGroupRule:configuration.getSecurityGroupRulesList()){
                            String securityGroupId = securityGroupRule.getSecurityGroupId();
                            for(String vm:securityGroupToVms.get(securityGroupId)){
                                int vmId=Integer.parseInt(vm);
                                SecurityGroup.SecurityGroupConfiguration.Direction direction = securityGroupRule.getDirection();
                                String remoteIpPrefix = securityGroupRule.getRemoteIpPrefix();
                                if(direction== SecurityGroup.SecurityGroupConfiguration.Direction.EGRESS){
                                    vmTopology.deleteEdgeFrom(vmId);
                                    vmTopology.add(vmId,Integer.parseInt(remoteIpPrefix),1);
                                }else {
                                    vmTopology.deleteEdgeTo(vmId);
                                    vmTopology.add(Integer.parseInt(remoteIpPrefix),vmId,1);
                                }
                            }
                        }
                    }else{

                    }
                }
            }

        }
    }

    public static int getSimilarity() {
        return 1;
    }
}
