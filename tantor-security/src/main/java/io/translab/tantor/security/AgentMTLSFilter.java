package io.translab.tantor.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

public class AgentMTLSFilter extends OncePerRequestFilter {

    private static final String AGENT_CN_PREFIX = "tantor-agent:";
    private final String expectedProxySecret;

    public AgentMTLSFilter(String expectedProxySecret) {
        this.expectedProxySecret = expectedProxySecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (isAgentPath(path)) {
            String proxySecret = request.getHeader("X-Proxy-Secret");
            String clientDN = request.getHeader("X-Forwarded-Client-DN");

            if (expectedProxySecret != null && expectedProxySecret.equals(proxySecret)
                    && clientDN != null && !clientDN.isBlank()) {
                String hostId = extractHostId(clientDN);
                if (hostId != null) {
                    PreAuthenticatedAuthenticationToken token = new PreAuthenticatedAuthenticationToken(
                            hostId, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))
                    );
                    token.setDetails(clientDN);
                    SecurityContextHolder.getContext().setAuthentication(token);
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAgentPath(String path) {
        return path != null && (path.equals("/api/v1/agents")
                || path.startsWith("/api/v1/agents/")
                || path.equals("/api/v1/ui/external-clusters/discovery")
                || path.startsWith("/api/v1/ui/external-clusters/discovery/"));
    }

    static String extractHostId(String clientDN) {
        try {
            LdapName name = new LdapName(clientDN);
            for (Rdn rdn : name.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    String commonName = String.valueOf(rdn.getValue()).trim();
                    if (commonName.startsWith(AGENT_CN_PREFIX)) {
                        String hostId = commonName.substring(AGENT_CN_PREFIX.length()).trim();
                        return hostId.isBlank() ? null : hostId;
                    }
                    return null;
                }
            }
        } catch (InvalidNameException ignored) {
            return null;
        }
        return null;
    }
}
