package com.gestaosla.backend.evidencia.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import com.gestaosla.backend.shared.api.RegrasAcesso;

@Configuration(proxyBeanMethods = false)
class EvidenciaRegrasAcesso {

    // Mesmos papéis dos @PreAuthorize de EvidenciaController. A listagem (GET /api/chamados/*/evidencias) já cai na regra
    // do chamado para os três papéis.
    @Bean
    RegrasAcesso regrasEvidencia() {
        return rotas -> {
            rotas.requestMatchers(HttpMethod.POST, "/api/chamados/*/evidencias").hasRole("TECNICO");
            rotas.requestMatchers(HttpMethod.GET, "/api/evidencias/**").hasAnyRole("GESTOR", "TECNICO", "CLIENTE");
            rotas.requestMatchers(HttpMethod.POST, "/api/evidencias/*/reprocessamento").hasRole("GESTOR");
        };
    }
}
