package com.gestaosla.backend.shared.internal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 401 e 403 nascem nos filtros de segurança, fora do Spring MVC. Este Customizer (aplicado pelo Spring Security
 * a toda cadeia de filtros) os repassa ao GlobalExceptionHandler, para saírem no mesmo formato dos demais erros.
 */
@Configuration(proxyBeanMethods = false)
class SegurancaErrosConfig {

    @Bean
    Customizer<HttpSecurity> errosDeSegurancaComoProblemDetails(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return http -> http.exceptionHandling(erros -> erros
                .authenticationEntryPoint((request, response, ex) -> resolver.resolveException(request, response, null, ex))
                .accessDeniedHandler((request, response, ex) -> resolver.resolveException(request, response, null, ex)));
    }
}
