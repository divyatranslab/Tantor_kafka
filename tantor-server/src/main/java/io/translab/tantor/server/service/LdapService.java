package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.LdapConfig;
import io.translab.tantor.server.dto.LdapDTOs;
import io.translab.tantor.server.security.EncryptionService;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class LdapService {
    private static final String ENCRYPTION_PREFIX = "enc:v1:";
    private final EncryptionService encryptionService;
    private static final int MAX_LDAP_TIMEOUT_SECONDS = 60;
    private static final String LDAP_READ_TIMEOUT = "com.sun.jndi.ldap.read.timeout";
    private static final String LDAP_CONNECT_TIMEOUT = "com.sun.jndi.ldap.connect.timeout";
    private static final Logger log = LoggerFactory.getLogger(LdapService.class);

    public LdapService(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    int getValidatedTimeoutMillis(LdapConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("LDAP configuration must not be null");
        }
        int timeoutSeconds = config.getConnectionTimeout();
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_LDAP_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "LDAP connection timeout must be between 1 and " + MAX_LDAP_TIMEOUT_SECONDS + " seconds");
        }
        return Math.multiplyExact(timeoutSeconds, 1000);
    }

    /**
     * Create JNDI Environment Properties
     */
    Hashtable<String, String> createEnv(LdapConfig config, String principal, String credentials) {
        Hashtable<String, String> env = new Hashtable<>();
        int timeoutMillis = getValidatedTimeoutMillis(config);
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.getServerUrl());

        if (principal != null) {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, principal);
            if (credentials != null) {
                env.put(Context.SECURITY_CREDENTIALS, credentials);
            }
        } else {
            env.put(Context.SECURITY_AUTHENTICATION, "none");
        }

        if (config.isUseSsl()) {
            env.put(Context.SECURITY_PROTOCOL, "ssl");
            if (!config.isTlsValidateCert()) {
                // To support disable cert validation, would need a custom SocketFactory.
                // For simplicity, we assume standard JSSE truststore is configured.
            }
        }
        env.put(LDAP_CONNECT_TIMEOUT, String.valueOf(timeoutMillis));
        env.put(LDAP_READ_TIMEOUT, String.valueOf(timeoutMillis));
        return env;
    }

    DirContext createDirContext(Hashtable<String, String> env) throws NamingException {
        return new InitialDirContext(env);
    }

    /**
     * Test LDAP connection with bind user
     */
    public LdapDTOs.LdapTestResponse testConnection(LdapConfig config, String bindPassword) {
        try {
            Hashtable<String, String> env = createEnv(config, config.getBindDn(), bindPassword);
            DirContext ctx = createDirContext(env);
            ctx.close();
            return new LdapDTOs.LdapTestResponse(true, "Successfully bound to LDAP server", null, new ArrayList<>());
        } catch (IllegalArgumentException e) {
            log.warn("LDAP connection test rejected due to invalid configuration");
            return new LdapDTOs.LdapTestResponse(
                    false,
                    "Invalid LDAP configuration.",
                    null,
                    new ArrayList<>());
        } catch (Exception e) {
            log.error("LDAP connection test failed (exceptionType={})",
                    e.getClass().getSimpleName());
            return new LdapDTOs.LdapTestResponse(
                    false,
                    "Connection failed",
                    null,
                    new ArrayList<>());
        }
    }

    /**
     * Authenticate user and fetch groups
     */
    private String escapeLdapFilterValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Username must not be null");
        }
        StringBuilder escaped = new StringBuilder();

        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\':
                    escaped.append("\\5c");
                    break;
                case '*':
                    escaped.append("\\2a");
                    break;
                case '(':
                    escaped.append("\\28");
                    break;
                case ')':
                    escaped.append("\\29");
                    break;
                case '\0':
                    escaped.append("\\00");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }

    public LdapDTOs.LdapTestResponse authenticate(String username, String password, LdapConfig config,
            String bindPassword) {
        DirContext bindCtx = null;
        try {
            // 1. Bind with Admin / Bind User
            Hashtable<String, String> env = createEnv(config, config.getBindDn(), bindPassword);
            bindCtx = createDirContext(env);

            // 2. Search for User
            String safeUserName = escapeLdapFilterValue(username);
            String searchFilter = config.getUserSearchFilter().replace("{username}", safeUserName);
            SearchControls searchControls = new SearchControls();
            searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            searchControls.setReturningAttributes(new String[] { "dn" });
            searchControls.setTimeLimit(getValidatedTimeoutMillis(config));

            String userDn;

            NamingEnumeration<SearchResult> results = null;

            try {
                results = bindCtx.search(config.getUserSearchBase(), searchFilter, searchControls);
                if (!results.hasMore()) {
                    return new LdapDTOs.LdapTestResponse(false,
                            "User not found in directory",
                            null,
                            new ArrayList<>());
                }
                // Read the first matching entry
                SearchResult searchResult = results.next();

                if (results.hasMore()) {
                    return new LdapDTOs.LdapTestResponse(false,
                            "Multiple users found in directory, authentication is ambiguous",
                            null,
                            new ArrayList<>());
                }

                userDn = searchResult.getNameInNamespace();
            } finally {
                if (results != null) {
                    try {
                        results.close();
                    } catch (NamingException ignore) {

                    }
                }
            }

            // 3. Authenticate as the User
            Hashtable<String, String> userEnv = createEnv(config, userDn, password);
            DirContext userCtx = createDirContext(userEnv);
            userCtx.close();

            // 4. Optionally search for groups (if configured)
            List<String> groups = new ArrayList<>();
            if (config.getGroupSearchBase() != null && !config.getGroupSearchBase().isEmpty()) {
                SearchControls groupControls = new SearchControls();
                groupControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                groupControls.setReturningAttributes(new String[] { "dn", "cn" });
                groupControls.setTimeLimit(getValidatedTimeoutMillis(config));

                NamingEnumeration<SearchResult> groupResults = null;
                try {
                    groupResults = bindCtx.search(config.getGroupSearchBase(),
                            "(member={0})",
                            new Object[] { userDn },
                            groupControls);
                    while (groupResults.hasMore()) {
                        SearchResult groupResult = groupResults.next();
                        groups.add(groupResult.getNameInNamespace());
                    }
                } finally {
                    if (groupResults != null) {
                        try {
                            groupResults.close();
                        } catch (NamingException ignore) {

                        }
                    }

                }

            }

            return new LdapDTOs.LdapTestResponse(true, "Authentication successful", userDn, groups);

        } catch (Exception e) {
            log.error("LDAP authentication operation failed (exceptionType={})", e.getClass().getSimpleName());
            return new LdapDTOs.LdapTestResponse(false, "Authentication failed.", null,
                    new ArrayList<>());
        } finally {
            if (bindCtx != null) {
                try {
                    bindCtx.close();
                } catch (NamingException ignored) {
                }
            }
        }
    }

    public String encryptPassword(String plainText) {
        if (plainText == null)
            return null;
        return ENCRYPTION_PREFIX + encryptionService.encrypt(plainText);
    }

    public String decryptPassword(String encrypted) {
        if (encrypted == null)
            return null;
        if (encrypted.startsWith(ENCRYPTION_PREFIX)) {
            return encryptionService.decrypt(encrypted.substring(ENCRYPTION_PREFIX.length()));
        }
        // Read legacy Base64 values once so an administrator can re-save the
        // configuration, which migrates them to AES-GCM encryption.
        return new String(java.util.Base64.getDecoder().decode(encrypted), java.nio.charset.StandardCharsets.UTF_8);
    }
}
