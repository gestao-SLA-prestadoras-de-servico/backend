package com.gestaosla.backend.outbox.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.outbox.publicador.*} do application.properties. O intervalo ({@code app.outbox.publicador.intervalo}) é
 * lido direto pelo {@code @Scheduled}.
 */
@ConfigurationProperties(prefix = "app.outbox.publicador")
record PublicadorOutboxProperties(int tamanhoLote, Duration esperaConfirmacao) {
}
