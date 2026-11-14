package com.gestaosla.backend.chamado.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gestaosla.backend.shared.api.RegrasAcesso;

@Configuration(proxyBeanMethods = false)
class ChamadoRegrasAcesso {

    // Mesmos papéis do @PreAuthorize do SlaChamadoController; o filtro por cliente e técnico fica no controller.
    @Bean
    RegrasAcesso regrasChamado() {
        return rotas -> rotas.requestMatchers("/api/chamados/*/sla").hasAnyRole("GESTOR", "TECNICO", "CLIENTE");
    }
}
