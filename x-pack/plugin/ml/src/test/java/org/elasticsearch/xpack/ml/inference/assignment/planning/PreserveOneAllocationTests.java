/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.inference.assignment.planning;

import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ml.inference.assignment.planning.AssignmentPlan.Deployment;
import org.elasticsearch.xpack.ml.inference.assignment.planning.AssignmentPlan.Node;

import java.util.List;
import java.util.Map;

import static org.elasticsearch.test.hamcrest.OptionalMatchers.isEmpty;
import static org.elasticsearch.test.hamcrest.OptionalMatchers.isPresentWith;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class PreserveOneAllocationTests extends ESTestCase {

    public void testGivenNoPreviousAssignments() {
        Node node1 = new Node("n_1", ByteSizeValue.ofMb(440).getBytes(), 4);
        Node node2 = new Node("n_2", ByteSizeValue.ofMb(440).getBytes(), 4);
        Deployment deployment1 = new AssignmentPlan.Deployment("m_1", ByteSizeValue.ofMb(30).getBytes(), 2, 1, Map.of(), 0, null, 0, 0);
        AssignmentPlan.Deployment deployment2 = new Deployment("m_2", ByteSizeValue.ofMb(30).getBytes(), 2, 4, Map.of(), 0, null, 0, 0);
        PreserveOneAllocation preserveOneAllocation = new PreserveOneAllocation(List.of(node1, node2), List.of(deployment1, deployment2));

        List<Node> nodesPreservingAllocations = preserveOneAllocation.nodesPreservingAllocations();
        assertThat(nodesPreservingAllocations, contains(node1, node2));

        List<AssignmentPlan.Deployment> modelsPreservingAllocations = preserveOneAllocation.modelsPreservingAllocations();
        assertThat(modelsPreservingAllocations, contains(deployment1, deployment2));
    }

    public void testGivenPreviousAssignments() {
        {
            // old memory format
            Node node1 = new Node("n_1", ByteSizeValue.ofMb(640).getBytes(), 8);
            Node node2 = new Node("n_2", ByteSizeValue.ofMb(640).getBytes(), 8);
            Deployment deployment1 = new Deployment("m_1", ByteSizeValue.ofMb(30).getBytes(), 2, 1, Map.of("n_1", 1), 1, null, 0, 0);
            Deployment deployment2 = new Deployment(
                "m_2",
                ByteSizeValue.ofMb(50).getBytes(),
                6,
                4,
                Map.of("n_1", 1, "n_2", 2),
                3,
                null,
                0,
                0
            );
            PreserveOneAllocation preserveOneAllocation = new PreserveOneAllocation(
                List.of(node1, node2),
                List.of(deployment1, deployment2)
            );

            List<Node> nodesPreservingAllocations = preserveOneAllocation.nodesPreservingAllocations();
            assertThat(nodesPreservingAllocations, hasSize(2));

            assertThat(nodesPreservingAllocations.get(0).id(), equalTo("n_1"));
            // 640 - [(30*2+240)+(50*2+240)] = 0 : deployments use all memory on the node
            assertThat(nodesPreservingAllocations.get(0).availableMemoryBytes(), equalTo(0L));
            // 8 - (1*1+1*4) = 3 : deployments use 5 cores on the node
            assertThat(nodesPreservingAllocations.get(0).cores(), equalTo(3));

            assertThat(nodesPreservingAllocations.get(1).id(), equalTo("n_2"));
            // 640 - (50*2+240) = 300 : deployments use 340MB on the node
            assertThat(nodesPreservingAllocations.get(1).availableMemoryBytes(), equalTo(ByteSizeValue.ofMb(300).getBytes()));
            // 8 - (1*4) = 4 : preserving 1 allocation of deployment 2 should use 4 cores on the node
            assertThat(nodesPreservingAllocations.get(1).cores(), equalTo(4));

            List<AssignmentPlan.Deployment> modelsPreservingAllocations = preserveOneAllocation.modelsPreservingAllocations();
            assertThat(modelsPreservingAllocations, hasSize(2));

            assertThat(modelsPreservingAllocations.get(0).deploymentId(), equalTo("m_1"));
            assertThat(modelsPreservingAllocations.get(0).memoryBytes(), equalTo(ByteSizeValue.ofMb(30).getBytes()));
            assertThat(modelsPreservingAllocations.get(0).perDeploymentMemoryBytes(), equalTo(ByteSizeValue.ofMb(0).getBytes()));
            assertThat(modelsPreservingAllocations.get(0).perAllocationMemoryBytes(), equalTo(ByteSizeValue.ofMb(0).getBytes()));
            assertThat(modelsPreservingAllocations.get(0).allocations(), equalTo(1));
            assertThat(modelsPreservingAllocations.get(0).threadsPerAllocation(), equalTo(1));
            assertThat(modelsPreservingAllocations.get(0).currentAllocationsByNodeId(), equalTo(Map.of("n_1", 0)));

            assertThat(modelsPreservingAllocations.get(1).deploymentId(), equalTo("m_2"));
            assertThat(modelsPreservingAllocations.get(1).memoryBytes(), equalTo(ByteSizeValue.ofMb(50).getBytes()));
            assertThat(modelsPreservingAllocations.get(1).perDeploymentMemoryBytes(), equalTo(ByteSizeValue.ofMb(0).getBytes()));
            assertThat(modelsPreservingAllocations.get(1).perAllocationMemoryBytes(), equalTo(ByteSizeValue.ofMb(0).getBytes()));
            assertThat(modelsPreservingAllocations.get(1).allocations(), equalTo(4));
            assertThat(modelsPreservingAllocations.get(1).threadsPerAllocation(), equalTo(4));
            assertThat(modelsPreservingAllocations.get(1).currentAllocationsByNodeId(), equalTo(Map.of("n_1", 0, "n_2", 1)));

            // Now we have a plan with 2 deployments assigned to 2 nodes.
            // Note that deployment 1 has already 1 allocation on node 1, and it gets 2 more. It's more than 2 allocations defined during
            // initialization of deployment1, but we don't care at this point.
            AssignmentPlan plan = AssignmentPlan.builder(List.of(node1, node2), List.of(deployment1, deployment2))
                .assignModelToNode(deployment1, node1, 2)
                .assignModelToNode(deployment2, node2, 1)
                .build();
            assertThat(plan.assignments(deployment1).get(), equalTo(Map.of(node1, 2)));
            assertThat(plan.assignments(deployment2).get(), equalTo(Map.of(node2, 1)));

            plan = preserveOneAllocation.mergePreservedAllocations(plan);

            assertThat(plan.assignments(deployment1).get(), equalTo(Map.of(node1, 3)));
            assertThat(plan.assignments(deployment2).get(), equalTo(Map.of(node1, 1, node2, 2)));
            // Node 1 already had deployments 1 and 2 assigned to it so adding more allocation doesn't change memory usage.
            assertThat(plan.getRemainingNodeMemory("n_1"), equalTo(0L));
            // 8 - ((1*1+1*4) + 2*1) = 1 : deployments use 7 cores on the node
            assertThat(plan.getRemainingNodeCores("n_1"), equalTo(1));
            // Node 2 already had deployment 2 assigned to it so adding more allocation doesn't change memory usage.
            assertThat(plan.getRemainingNodeMemory("n_2"), equalTo(ByteSizeValue.ofMb(300).getBytes()));
            // 8 - [(1*4) + (1*4)] = 4 : deployment 2 should use all cores on the node
            assertThat(plan.getRemainingNodeCores("n_2"), equalTo(0));
        }
        {
            // new memory format
            Node node1 = new Node("n_1", ByteSizeValue.ofMb(1000).getBytes(), 8);
            Node node2 = new Node("n_2", ByteSizeValue.ofMb(1000).getBytes(), 8);
            Deployment deployment1 = new Deployment(
                "m_1",
                ByteSizeValue.ofMb(30).getBytes(),
                2,
                1,
                Map.of("n_1", 1),
                1,
                null,
                ByteSizeValue.ofMb(300).getBytes(),
                ByteSizeValue.ofMb(10).getBytes()
            );
            Deployment deployment2 = new Deployment(
                "m_2",
                ByteSizeValue.ofMb(50).getBytes(),
                6,
                4,
                Map.of("n_1", 1, "n_2", 2),
                3,
                null,
                ByteSizeValue.ofMb(300).getBytes(),
                ByteSizeValue.ofMb(10).getBytes()
            );
            PreserveOneAllocation preserveOneAllocation = new PreserveOneAllocation(
                List.of(node1, node2),
                List.of(deployment1, deployment2)
            );

            List<Node> nodesPreservingAllocations = preserveOneAllocation.nodesPreservingAllocations();
            assertThat(nodesPreservingAllocations, hasSize(2));

            assertThat(nodesPreservingAllocations.get(0).id(), equalTo("n_1"));
            // 1000 - [(30+300+10)+(50 + 300 +10)] = 300 : deployments use 700 memory on the node
            assertThat(nodesPreservingAllocations.get(0).availableMemoryBytes(), equalTo(ByteSizeValue.ofMb(300).getBytes()));
            // 8 - (1*1+1*4) = 3 : deployments use 5 cores on the node
            assertThat(nodesPreservingAllocations.get(0).cores(), equalTo(3));

            assertThat(nodesPreservingAllocations.get(1).id(), equalTo("n_2"));
            // 1000 - (50 +300 + 2*10) = 630 : deployments use 340MB on the node
            assertThat(nodesPreservingAllocations.get(1).availableMemoryBytes(), equalTo(ByteSizeValue.ofMb(630).getBytes()));
            // 8 - (1*4) = 0 : preserving 1 allocation of deployment 2 should use 4 cores on the node
            assertThat(nodesPreservingAllocations.get(1).cores(), equalTo(4));

            List<AssignmentPlan.Deployment> modelsPreservingAllocations = preserveOneAllocation.modelsPreservingAllocations();
            assertThat(modelsPreservingAllocations, hasSize(2));

            assertThat(modelsPreservingAllocations.get(0).deploymentId(), equalTo("m_1"));
            assertThat(modelsPreservingAllocations.get(0).memoryBytes(), equalTo(ByteSizeValue.ofMb(30).getBytes()));
            assertThat(modelsPreservingAllocations.get(0).perDeploymentMemoryBytes(), equalTo(ByteSizeValue.ofMb(300).getBytes()));
            assertThat(modelsPreservingAllocations.get(0).perAllocationMemoryBytes(), equalTo(ByteSizeValue.ofMb(10).getBytes()));
            assertThat(modelsPreservingAllocations.get(0).allocations(), equalTo(1));
            assertThat(modelsPreservingAllocations.get(0).threadsPerAllocation(), equalTo(1));
            assertThat(modelsPreservingAllocations.get(0).currentAllocationsByNodeId(), equalTo(Map.of("n_1", 0)));

            assertThat(modelsPreservingAllocations.get(1).deploymentId(), equalTo("m_2"));
            assertThat(modelsPreservingAllocations.get(1).memoryBytes(), equalTo(ByteSizeValue.ofMb(50).getBytes()));
            assertThat(modelsPreservingAllocations.get(1).perDeploymentMemoryBytes(), equalTo(ByteSizeValue.ofMb(300).getBytes()));
            assertThat(modelsPreservingAllocations.get(1).perAllocationMemoryBytes(), equalTo(ByteSizeValue.ofMb(10).getBytes()));
            assertThat(modelsPreservingAllocations.get(1).allocations(), equalTo(4));
            assertThat(modelsPreservingAllocations.get(1).threadsPerAllocation(), equalTo(4));
            assertThat(modelsPreservingAllocations.get(1).currentAllocationsByNodeId(), equalTo(Map.of("n_1", 0, "n_2", 1)));

            // Now we have a plan with 2 deployments assigned to 2 nodes.
            // Note that deployment 1 has already 1 allocation on node 1, and it gets 2 more. It's more than 2 allocations defined during
            // initialization of deployment1, but we don't care at this point.
            AssignmentPlan plan = AssignmentPlan.builder(List.of(node1, node2), List.of(deployment1, deployment2))
                .assignModelToNode(deployment1, node1, 2)
                .assignModelToNode(deployment2, node2, 1)
                .build();
            assertThat(plan.assignments(deployment1).get(), equalTo(Map.of(node1, 2)));
            assertThat(plan.assignments(deployment2).get(), equalTo(Map.of(node2, 1)));

            plan = preserveOneAllocation.mergePreservedAllocations(plan);

            assertThat(plan.assignments(deployment1).get(), equalTo(Map.of(node1, 3)));
            assertThat(plan.assignments(deployment2).get(), equalTo(Map.of(node1, 1, node2, 2)));
            // 1000 - [(30+300+3*10) + (50+300+10)] = 280 : deployments use 720MB on the node
            assertThat(plan.getRemainingNodeMemory("n_1"), equalTo(ByteSizeValue.ofMb(280).getBytes()));
            // 8 - ((1*1+1*4) + 2*1) = 1 : deployments use 7 cores on the node
            assertThat(plan.getRemainingNodeCores("n_1"), equalTo(1));
            // 1000 - (50 + 300 + 2*10) = 630 : deployments use 370MB on the node
            assertThat(plan.getRemainingNodeMemory("n_2"), equalTo(ByteSizeValue.ofMb(630).getBytes()));
            // 8 - [(1*4) + (1*4)] = 4 : deployment 2 should use all cores on the node
            assertThat(plan.getRemainingNodeCores("n_2"), equalTo(0));

        }
    }

    public void testGivenModelWithPreviousAssignments_AndPlanToMergeHasNoAssignments() {
        {
            // old memory format
            Node node = new Node("n_1", ByteSizeValue.ofMb(400).getBytes(), 4);
            Deployment deployment = new Deployment("m_1", ByteSizeValue.ofMb(30).getBytes(), 2, 2, Map.of("n_1", 2), 2, null, 0, 0);
            PreserveOneAllocation preserveOneAllocation = new PreserveOneAllocation(List.of(node), List.of(deployment));

            AssignmentPlan plan = AssignmentPlan.builder(List.of(node), List.of(deployment)).build();
            assertThat(plan.assignments(deployment), isEmpty());

            plan = preserveOneAllocation.mergePreservedAllocations(plan);
            assertThat(plan.assignments(deployment), isPresentWith(Map.of(node, 1)));
            // 400 - (30*2 + 240) = 100 : deployments use 300MB on the node
            assertThat(plan.getRemainingNodeMemory("n_1"), equalTo(ByteSizeValue.ofMb(100).getBytes()));
            assertThat(plan.getRemainingNodeCores("n_1"), equalTo(2));
        }
        {
            // new memory format
            Node node = new Node("n_1", ByteSizeValue.ofMb(400).getBytes(), 4);
            Deployment deployment = new Deployment(
                "m_1",
                ByteSizeValue.ofMb(30).getBytes(),
                2,
                2,
                Map.of("n_1", 2),
                2,
                null,
                ByteSizeValue.ofMb(300).getBytes(),
                ByteSizeValue.ofMb(10).getBytes()
            );
            PreserveOneAllocation preserveOneAllocation = new PreserveOneAllocation(List.of(node), List.of(deployment));

            AssignmentPlan plan = AssignmentPlan.builder(List.of(node), List.of(deployment)).build();
            assertThat(plan.assignments(deployment), isEmpty());

            plan = preserveOneAllocation.mergePreservedAllocations(plan);
            assertThat(plan.assignments(deployment), isPresentWith(Map.of(node, 1)));
            // 400 - (30 + 300 + 10) = 60 : deployments use 340MB on the node
            assertThat(plan.getRemainingNodeMemory("n_1"), equalTo(ByteSizeValue.ofMb(60).getBytes()));
            assertThat(plan.getRemainingNodeCores("n_1"), equalTo(2));
        }
    }
}
