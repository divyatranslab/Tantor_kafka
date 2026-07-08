import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class UnbindAgent {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/tantor";
        String user = "postgres";
        String password = "password"; // Wait, I don't know the password!
        try (Connection conn = DriverManager.getConnection(url, user, "")) { // try no password or 'postgres'
            try (Statement stmt = conn.createStatement()) {
                int rows = stmt.executeUpdate("UPDATE kf_discovery_agents SET cluster_id = NULL WHERE hostname = '192.168.3.149'");
                System.out.println("Unbound " + rows + " agents.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
