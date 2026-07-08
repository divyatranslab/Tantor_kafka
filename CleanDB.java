import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class CleanDB {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/tantor";
        String user = "postgres";
        String password = "jayesh123";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            
            System.out.println("Cleaning database...");
            String[] tables = {
                "activity_alerts",
                "audit_logs",
                "cluster_services",
                "job_steps",
                "agent_tasks",
                "jobs",
                "hosts",
                "clusters"
            };
            
            for (String table : tables) {
                try {
                    int rows = stmt.executeUpdate("DELETE FROM " + table);
                    System.out.println("Deleted " + rows + " rows from " + table);
                } catch (Exception e) {
                    System.out.println("Could not clear table " + table + ": " + e.getMessage());
                }
            }
            System.out.println("Database cleanup complete!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
