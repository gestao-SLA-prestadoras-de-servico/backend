package com.gestaosla.backend.cadastro.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gestaosla.backend.shared.api.RegrasAcesso;

@Configuration(proxyBeanMethods = false)
class CadastroRegrasAcesso {

    // Mesmo papel do @PreAuthorize do ClienteController.
    @Bean
    RegrasAcesso regrasCadastro() {
        return rotas -> rotas.requestMatchers("/api/clientes", "/api/clientes/**").hasRole("GESTOR");
    }
}
