package com.gestaosla.backend.chamado.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import com.gestaosla.backend.shared.api.RegrasAcesso;

@Configuration(proxyBeanMethods = false)
class ChamadoRegrasAcesso {

    // Mesmos papéis dos @PreAuthorize de ChamadoController e SlaChamadoController; o escopo por cliente e técnico fica
    // na ConsultaChamadoService.
    @Bean
    RegrasAcesso regrasChamado() {
        return rotas -> {
            rotas.requestMatchers(HttpMethod.POST, "/api/chamados").hasRole("CLIENTE");
            rotas.requestMatchers(HttpMethod.PUT, "/api/chamados/*/prioridade").hasRole("GESTOR");
            rotas.requestMatchers(HttpMethod.POST, "/api/chamados/*/posicionamento", "/api/chamados/*/atribuicao").hasRole("GESTOR");
            rotas.requestMatchers(HttpMethod.GET, "/api/chamados", "/api/chamados/**").hasAnyRole("GESTOR", "TECNICO", "CLIENTE");
        };
    }
}
