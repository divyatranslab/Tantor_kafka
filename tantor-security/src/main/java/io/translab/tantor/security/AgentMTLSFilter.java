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

public class AgentMTLSFilter extends OncePerRequestFilter {

    private final String expectedProxySecret;

    public AgentMTLSFilter(String expectedProxySecret) {
        this.expectedProxySecret = expectedProxySecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/v1/agents/")) {
            String proxySecret = request.getHeader("X-Proxy-Secret");
            String clientDN = request.getHeader("X-Forwarded-Client-DN");
            


            if (expectedProxySecret != null && expectedProxySecret.equals(proxySecret)
                    && clientDN != null && !clientDN.isBlank()) {
                
                // Assuming clientDN contains CN=tantor-agent or similar, 
                // we grant the AGENT role.
                PreAuthenticatedAuthenticationToken token = new PreAuthenticatedAuthenticationToken(
                        clientDN, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))
                );
                SecurityContextHolder.getContext().setAuthentication(token);
            }
        }

        filterChain.doFilter(request, response);
    }
}
