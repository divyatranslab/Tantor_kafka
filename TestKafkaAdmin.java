import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import java.util.Properties;

public class TestKafkaAdmin {
    public static void main(String[] args) {
        String path = args[0];
        String password = args[1];

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "192.168.3.222:9093");
        
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.mechanism", "SCRAM-SHA-512");
        props.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"admin\" password=\"password\";");
        
        props.put("ssl.truststore.type", "PKCS12");
        props.put("ssl.truststore.location", path);
        props.put("ssl.truststore.password", password);
        props.put("ssl.endpoint.identification.algorithm", "");

        System.out.println("Starting AdminClient test...");
        try (AdminClient client = AdminClient.create(props)) {
            DescribeClusterResult clusterResult = client.describeCluster();
            String clusterId = clusterResult.clusterId().get();
            System.out.println("Successfully connected! Cluster ID: " + clusterId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
