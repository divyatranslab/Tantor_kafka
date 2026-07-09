import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.QuorumInfo;
import org.apache.kafka.common.Node;

import java.util.Properties;
import java.util.concurrent.ExecutionException;

public class KafkaCheck {
    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "192.168.3.161:9095");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");

        try (AdminClient client = AdminClient.create(props)) {
            System.out.println("Connecting to 192.168.3.161:9095...");
            DescribeClusterResult cluster = client.describeCluster();
            
            System.out.println("Cluster ID: " + cluster.clusterId().get());
            System.out.println("Active Controller: " + cluster.controller().get());
            System.out.println("Nodes:");
            for (Node node : cluster.nodes().get()) {
                System.out.println("  - Node " + node.id() + ": " + node.host() + ":" + node.port());
            }

            try {
                QuorumInfo quorum = client.describeMetadataQuorum().quorumInfo().get();
                System.out.println("KRaft Quorum Voters:");
                for (QuorumInfo.ReplicaState voter : quorum.voters()) {
                    System.out.println("  - Voter Node ID: " + voter.replicaId());
                }
            } catch (Exception e) {
                System.out.println("Failed to get KRaft quorum: " + e.getMessage());
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
