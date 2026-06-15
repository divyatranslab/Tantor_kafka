import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class TestJackson {
    public static void main(String[] args) throws Exception {
        String json = "{\"listener_port\":9088}";
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(json, Map.class);
        System.out.println("Type: " + map.get("listener_port").getClass().getName());
        Integer port = (Integer) map.get("listener_port");
        System.out.println("Port: " + port);
    }
}
