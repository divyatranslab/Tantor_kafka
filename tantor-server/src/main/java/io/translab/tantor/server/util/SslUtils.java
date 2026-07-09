package io.translab.tantor.server.util;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

public class SslUtils {

    public static SSLContext createSslContextFromPem(String pemCertificate) throws Exception {
        if (pemCertificate == null || pemCertificate.isBlank()) {
            return SSLContext.getDefault();
        }

        // Clean up PEM format
        String certStr = pemCertificate
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        
        byte[] certBytes = Base64.getDecoder().decode(certStr);
        
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
        
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null); // Initialize empty keystore
        trustStore.setCertificateEntry("custom-cert", cert);
        
        return createSslContextFromTrustStore(trustStore);
    }

    public static SSLContext createSslContextFromPkcs12(String base64Pkcs12, String password) throws Exception {
        if (base64Pkcs12 == null || base64Pkcs12.isBlank()) {
            return SSLContext.getDefault();
        }

        byte[] certBytes = Base64.getDecoder().decode(base64Pkcs12);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        char[] passChars = password != null ? password.toCharArray() : new char[0];
        trustStore.load(new ByteArrayInputStream(certBytes), passChars);

        return createSslContextFromTrustStore(trustStore);
    }

    private static SSLContext createSslContextFromTrustStore(KeyStore trustStore) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new java.security.SecureRandom());
        
        return sslContext;
    }
}
