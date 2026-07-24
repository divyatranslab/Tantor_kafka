package io.translab.tantor.artifact.config;

import io.translab.tantor.security.KeycloakRoleConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            @Value("${tantor.security.jwt.audience:}") String requiredAudience) throws Exception {

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());

        JwtDecoder validatingDecoder = token -> validateAudience(jwtDecoder.decode(token), requiredAudience);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/**", "/error", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/artifacts/**", "/api/v1/bundles/export").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(validatingDecoder)
                        .jwtAuthenticationConverter(authenticationConverter)));

        return http.build();
    }

    private Jwt validateAudience(Jwt jwt, String requiredAudience) {
        if (requiredAudience == null || requiredAudience.isBlank()
                || jwt.getAudience().contains(requiredAudience)) {
            return jwt;
        }
        OAuth2Error error = new OAuth2Error(
                "invalid_token", "The token audience is not accepted", null);
        throw new JwtValidationException("The token audience is not accepted", List.of(error));
    }
}
