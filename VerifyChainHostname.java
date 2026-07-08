import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;

public class VerifyChainHostname {
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

        System.out.println("Testing connection with HTTPS hostname verification...");
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            
            socket.startHandshake();
            System.out.println("Handshake successful!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
