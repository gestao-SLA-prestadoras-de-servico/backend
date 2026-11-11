package com.gestaosla.backend.shared.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Liga o {@code @Async}, usado por ouvintes de evento que chamam serviço externo depois do commit: assim a conexão da
 * transação que publicou o evento não fica presa à espera do serviço. Usa o executor do Spring Boot.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AssincronoConfig {
}
