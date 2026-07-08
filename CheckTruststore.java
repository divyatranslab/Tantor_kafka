import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Enumeration;

public class CheckTruststore {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java CheckTruststore <path-to-p12-file> <password>");
            return;
        }
        
        String path = args[0];
        String password = args[1];

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(path)) {
            ks.load(fis, password.toCharArray());
        }

        Enumeration<String> aliases = ks.aliases();
        System.out.println("Loaded PKCS12 file successfully. Found aliases:");
        int count = 0;
        while (aliases.hasMoreElements()) {
            count++;
            String alias = aliases.nextElement();
            System.out.println("\n--- Alias: " + alias + " ---");
            if (ks.isCertificateEntry(alias)) {
                System.out.println("Entry Type: trustedCertEntry");
                Certificate cert = ks.getCertificate(alias);
                System.out.println("Certificate Type: " + cert.getType());
                if (cert instanceof java.security.cert.X509Certificate) {
                    java.security.cert.X509Certificate x509 = (java.security.cert.X509Certificate) cert;
                    System.out.println("Subject: " + x509.getSubjectX500Principal());
                    System.out.println("Issuer: " + x509.getIssuerX500Principal());
                }
            } else if (ks.isKeyEntry(alias)) {
                System.out.println("Entry Type: PrivateKeyEntry (This is a key, NOT a trusted CA!)");
                Certificate[] chain = ks.getCertificateChain(alias);
                if (chain != null) {
                    System.out.println("Certificate Chain Length: " + chain.length);
                    for (int i = 0; i < chain.length; i++) {
                        if (chain[i] instanceof java.security.cert.X509Certificate) {
                            java.security.cert.X509Certificate x509 = (java.security.cert.X509Certificate) chain[i];
                            System.out.println("  Chain [" + i + "] Subject: " + x509.getSubjectX500Principal());
                            System.out.println("  Chain [" + i + "] Issuer: " + x509.getIssuerX500Principal());
                        }
                    }
                }
            } else {
                System.out.println("Entry Type: Unknown");
            }
        }
        System.out.println("\nTotal entries: " + count);
    }
}
