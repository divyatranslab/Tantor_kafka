import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Enumeration;

public class CheckTruststoreKey {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        String password = args[1];

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(path)) {
            ks.load(fis, password.toCharArray());
        }

        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (ks.isCertificateEntry(alias)) {
                Certificate cert = ks.getCertificate(alias);
                System.out.println("Subject: " + ((java.security.cert.X509Certificate) cert).getSubjectX500Principal());
                System.out.println("Signature Algorithm: " + ((java.security.cert.X509Certificate) cert).getSigAlgName());
                if (cert.getPublicKey() instanceof RSAPublicKey) {
                    RSAPublicKey rsa = (RSAPublicKey) cert.getPublicKey();
                    System.out.println("Key Size: " + rsa.getModulus().bitLength() + " bits");
                }
            }
        }
    }
}
