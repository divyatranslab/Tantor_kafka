package io.translab.tantor.server.config;

import io.translab.tantor.server.security.JwtAuthenticationFilter;
import io.translab.tantor.server.security.JwtUtils;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final RequestMatcher INTERNAL_PROMETHEUS_PATH =
            new AntPathRequestMatcher("/internal/prometheus/**", HttpMethod.GET.name());
    private static final RequestMatcher UI_API_PATH =
            new AntPathRequestMatcher("/api/v1/ui/**");
    private static final RequestMatcher IPV4_LOOPBACK = new IpAddressMatcher("127.0.0.0/8");
    private static final RequestMatcher IPV6_LOOPBACK = new IpAddressMatcher("::1");
    private static final RequestMatcher LOOPBACK_INTERNAL_PROMETHEUS = request ->
            INTERNAL_PROMETHEUS_PATH.matches(request)
                    && (IPV4_LOOPBACK.matches(request) || IPV6_LOOPBACK.matches(request));

    private final JwtUtils jwtUtils;

    @Value("${tantor.runtime.environment:production}")
    private String runtimeEnvironment;

    @Value("${tantor.agent.legacy-unauthenticated-enabled:false}")
    private boolean legacyUnauthenticatedAgentApiEnabled;

    /**
     * Transitional switch for the unsecured development VM only. Production
     * deployments must use Keycloak-issued JWTs for UI calls.
     */
    @Value("${tantor.ui.legacy-unauthenticated-enabled:false}")
    private boolean legacyUnauthenticatedUiApiEnabled;

    public SecurityConfig(JwtUtils jwtUtils) {
        this.jwtUtils = jwtUtils;
    }

    @Bean
    public JwtAuthenticationFilter authenticationJwtTokenFilter() {
        return new JwtAuthenticationFilter(jwtUtils);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                    // The login exchange and the minimal liveness endpoint are the
                    // only intentionally anonymous application routes.
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/monitoring/health").permitAll()
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                    // Agent transport authentication is controlled independently
                    // from browser authentication. When the explicit compatibility
                    // switch is enabled, existing internal and discovery agents may
                    // call only their machine endpoints without a user JWT.
                    .requestMatchers("/api/v1/agents/**")
                    .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                            legacyUnauthenticatedAgentApiEnabled))
                    .requestMatchers(HttpMethod.POST,
                            "/api/v1/ui/external-clusters/discovery/report",
                            "/api/v1/ui/external-clusters/discovery/heartbeat",
                            "/api/v1/ui/external-clusters/discovery/*/tasks/complete",
                            "/api/v1/ui/external-clusters/discovery/*/metrics",
                            "/api/v1/ui/clusters/external/*/tasks/complete",
                            "/api/v1/ui/clusters/external/*/tasks/metrics")
                    .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                            legacyUnauthenticatedAgentApiEnabled))
                    .requestMatchers(HttpMethod.GET,
                            "/api/v1/ui/external-clusters/discovery/*/tasks",
                            "/api/v1/ui/clusters/external/*/tasks")
                    .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                            legacyUnauthenticatedAgentApiEnabled))

                    // The current 194 development VM deliberately runs without
                    // Keycloak. Keep that compatibility explicitly scoped to its
                    // UI namespace; SIT/UAT/production still require JWT roles.
                    .requestMatchers(request -> legacyUnauthenticatedUiApiEnabled
                            && "development".equalsIgnoreCase(runtimeEnvironment)
                            && UI_API_PATH.matches(request))
                    .permitAll()

                    // Account/directory administration and API documentation are
                    // restricted before the general read/mutation rules below.
                    .requestMatchers("/api/v1/auth/users/**", "/api/v1/ldap/**").hasRole("ADMIN")
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").hasRole("ADMIN")

                    // The documented Prometheus service-discovery client runs on the
                    // Tantor server host. Preserve that private path while denying the
                    // same endpoint to every remote caller, including user JWTs.
                    .requestMatchers(LOOPBACK_INTERNAL_PROMETHEUS).permitAll()
                    .requestMatchers("/internal/prometheus/**").denyAll()

                    // Read-only API access is available to the established monitor and
                    // admin roles. Every state-changing API call requires admin.
                    .requestMatchers(HttpMethod.GET, "/api/v1/**")
                        .hasAnyRole("MONITOR", "ADMIN")
                    .requestMatchers(HttpMethod.HEAD, "/api/v1/**")
                        .hasAnyRole("MONITOR", "ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/v1/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasRole("ADMIN")

                    // Reject unclassified API methods and every non-API route unless a
                    // rule above deliberately grants it.
                    .requestMatchers("/api/v1/**").denyAll()
                    .anyRequest().denyAll());

        http.addFilterBefore(authenticationJwtTokenFilter(), UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
}
