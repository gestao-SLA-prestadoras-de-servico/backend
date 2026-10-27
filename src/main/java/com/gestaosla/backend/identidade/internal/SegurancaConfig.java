package com.gestaosla.backend.identidade.internal;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * API stateless autenticada pelo JWT do Keycloak (RF-01). Emissor, chaves e {@code aud} vêm do
 * application.properties; papéis e usuário inativo, do {@link UsuarioCorrenteConverter}. Endpoints restringem
 * o papel com {@code @PreAuthorize("hasRole('GESTOR')")} (RF-02).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SegurancaConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, UsuarioCorrenteConverter usuarioCorrente,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        // 401 e 403 nascem nos filtros, fora do Spring MVC: são repassados ao GlobalExceptionHandler para saírem
        // em Problem Details. O resource server tem entry point próprio (token inválido), por isso aparece duas vezes.
        AuthenticationEntryPoint naoAutenticado = (request, response, ex) -> resolver.resolveException(request, response, null, ex);
        AccessDeniedHandler acessoNegado = (request, response, ex) -> resolver.resolveException(request, response, null, ex);
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(usuarioCorrente))
                        .authenticationEntryPoint(naoAutenticado)
                        .accessDeniedHandler(acessoNegado))
                .exceptionHandling(erros -> erros
                        .authenticationEntryPoint(naoAutenticado)
                        .accessDeniedHandler(acessoNegado))
                .build();
    }

    // Só o frontend chama a API pelo navegador. O nome do bean é o que o .cors() procura.
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.origem}") String origem) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origem));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
