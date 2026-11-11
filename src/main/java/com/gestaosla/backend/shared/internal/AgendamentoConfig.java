package com.gestaosla.backend.shared.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga o {@code @Scheduled} das rotinas agendadas (ex.: publicador da outbox). Instância única (RN-116): sem trava
 * distribuída.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class AgendamentoConfig {
}
