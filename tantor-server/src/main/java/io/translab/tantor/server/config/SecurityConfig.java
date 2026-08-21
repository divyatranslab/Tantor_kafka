package io.translab.tantor.server.config;

import io.translab.tantor.server.security.JwtAuthenticationFilter;
import io.translab.tantor.server.security.JwtUtils;
import jakarta.servlet.DispatcherType;
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
    private static final RequestMatcher IPV4_LOOPBACK = new IpAddressMatcher("127.0.0.0/8");
    private static final RequestMatcher IPV6_LOOPBACK = new IpAddressMatcher("::1");
    private static final RequestMatcher LOOPBACK_INTERNAL_PROMETHEUS = request ->
            INTERNAL_PROMETHEUS_PATH.matches(request)
                    && (IPV4_LOOPBACK.matches(request) || IPV6_LOOPBACK.matches(request));

    private final JwtUtils jwtUtils;

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

                    // There is currently no certificate-to-principal filter for agents.
                    // Fail closed instead of allowing a user JWT (or no identity) to
                    // cross the agent trust boundary. C-03 owns agent mTLS identity.
                    .requestMatchers("/api/v1/agents/**").denyAll()

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
