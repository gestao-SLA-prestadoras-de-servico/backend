package com.gestaosla.backend.shared.api;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Regras de papel por rota que um módulo publica como bean. A cadeia de segurança aplica todas antes de ler o corpo
 * da requisição: quem não tem o papel recebe 403 sem passar pela validação. O {@code @PreAuthorize} no método continua
 * como segunda barreira.
 *
 * <pre>{@code
 * @Bean
 * RegrasAcesso regrasCadastro() {
 *     return rotas -> rotas.requestMatchers("/api/clientes", "/api/clientes/**").hasRole("GESTOR");
 * }
 * }</pre>
 */
@FunctionalInterface
public interface RegrasAcesso {

    void aplicar(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry rotas);
}
