import java.sql.Connection;
import java.sql.DriverManager;

public class DeleteConnections {
    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/tantor", "postgres", "jayesh123")) {
            int rows = conn.createStatement().executeUpdate("DELETE FROM kf_data_service_connections");
            System.out.println("SUCCESSFULLY DELETED " + rows + " CONNECTIONS!");
        }
    }
}
