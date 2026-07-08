import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

public class VerifyChain {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        String password = args[1];
        String host = "192.168.3.222";
        int port = 9093;

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(path)) {
            ks.load(fis, password.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tmf.getTrustManagers(), null);
        SSLSocketFactory factory = sc.getSocketFactory();

        System.out.println("Testing connection using the truststore...");
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            socket.startHandshake();
            System.out.println("Handshake successful! The truststore is 100% valid for this broker.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
