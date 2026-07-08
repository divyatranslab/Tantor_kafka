import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class FetchHostId {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/tantor";
        String user = "postgres";
        String password = "changeit"; // Try common passwords or postgres

        // Wait, application.yml says: password: ${TANTOR_DB_PASSWORD:postgres}
        password = "jayesh123";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, hostname, agent_name FROM hosts")) {
            System.out.println("Host IDs from database:");
            while (rs.next()) {
                System.out.println("ID: " + rs.getString("id") + ", Hostname: " + rs.getString("hostname") + ", Agent: " + rs.getString("agent_name"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
