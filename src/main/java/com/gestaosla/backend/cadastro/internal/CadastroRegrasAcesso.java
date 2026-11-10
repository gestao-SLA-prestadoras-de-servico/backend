package com.gestaosla.backend.cadastro.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gestaosla.backend.shared.api.RegrasAcesso;

@Configuration(proxyBeanMethods = false)
class CadastroRegrasAcesso {

    // Mesmos papéis dos @PreAuthorize de ClienteController, ContratoController e PoliticaController.
    @Bean
    RegrasAcesso regrasCadastro() {
        return rotas -> {
            rotas.requestMatchers("/api/clientes", "/api/clientes/**", "/api/contratos", "/api/contratos/**",
                    "/api/politicas", "/api/politicas/**").hasRole("GESTOR");
            rotas.requestMatchers("/api/me/contratos").hasRole("CLIENTE");
        };
    }
}
