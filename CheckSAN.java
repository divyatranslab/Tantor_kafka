import javax.net.ssl.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

public class CheckSAN {
    public static void main(String[] args) throws Exception {
        String host = "192.168.3.222";
        int port = 9093;
        
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }
        };

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        SSLSocketFactory factory = sc.getSocketFactory();

        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            socket.startHandshake();
            Certificate[] certs = socket.getSession().getPeerCertificates();
            X509Certificate cert = (X509Certificate) certs[0];
            System.out.println("Subject: " + cert.getSubjectX500Principal());
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                System.out.println("SANs:");
                for (List<?> san : sans) {
                    System.out.println("  Type: " + san.get(0) + " Value: " + san.get(1));
                }
            } else {
                System.out.println("No SANs found.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
