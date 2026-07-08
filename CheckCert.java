import javax.net.ssl.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

public class CheckCert {
    public static void main(String[] args) throws Exception {
        String host = "192.168.3.149";
        int port = 9095;
        
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

        System.out.println("Connecting to " + host + ":" + port + "...");
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, port)) {
            socket.startHandshake();
            Certificate[] certs = socket.getSession().getPeerCertificates();
            System.out.println("Server presented " + certs.length + " certificate(s):");
            for (int i = 0; i < certs.length; i++) {
                X509Certificate cert = (X509Certificate) certs[i];
                System.out.println("--- Certificate " + (i + 1) + " ---");
                System.out.println("Subject: " + cert.getSubjectX500Principal());
                System.out.println("Issuer: " + cert.getIssuerX500Principal());
                System.out.println("Serial Number: " + cert.getSerialNumber());
                System.out.println("Signature Algorithm: " + cert.getSigAlgName());
                System.out.println("Valid From: " + cert.getNotBefore());
                System.out.println("Valid Until: " + cert.getNotAfter());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
