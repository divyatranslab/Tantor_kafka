import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import java.util.Properties;

public class TestAdmin {
    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "192.168.3.149:9088");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
        
        try (AdminClient client = AdminClient.create(props)) {
            System.out.println("Client created. Listing topics...");
            client.listTopics(new ListTopicsOptions().listInternal(false)).names().get();
            System.out.println("Success!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
