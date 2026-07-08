import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.security.KeyStore;
import java.util.Enumeration;

public class TestDecode {
    public static void main(String[] args) throws Exception {
        String path = args[0]; // C:\Users\Translab\Downloads\truststore 6.p12
        byte[] original = Files.readAllBytes(Paths.get(path));
        
        // Simulate frontend readAsDataURL
        String base64 = Base64.getEncoder().encodeToString(original);
        
        // Simulate backend decode
        byte[] decoded = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        
        Path tempPath = Paths.get("temp_truststore.p12");
        Files.write(tempPath, decoded);
        
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(tempPath.toFile())) {
            ks.load(fis, "changeit".toCharArray());
        }
        Enumeration<String> aliases = ks.aliases();
        int count = 0;
        while (aliases.hasMoreElements()) {
            count++;
            aliases.nextElement();
        }
        System.out.println("Decoded successfully. Entries: " + count);
        Files.delete(tempPath);
    }
}
