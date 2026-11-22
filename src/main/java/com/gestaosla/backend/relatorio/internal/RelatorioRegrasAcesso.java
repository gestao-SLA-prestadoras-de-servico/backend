package com.gestaosla.backend.relatorio.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gestaosla.backend.shared.api.RegrasAcesso;

@Configuration(proxyBeanMethods = false)
class RelatorioRegrasAcesso {

    @Bean
    RegrasAcesso regrasRelatorio() {
        return rotas -> rotas.requestMatchers("/api/relatorios/**").hasRole("GESTOR");
    }
}
