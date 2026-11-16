package com.gestaosla.backend.evidencia.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.armazenamento.*} do application.properties; as credenciais vêm de MINIO_ACCESS_KEY e MINIO_SECRET_KEY. */
@ConfigurationProperties(prefix = "app.armazenamento")
record ArmazenamentoProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        Duration connectTimeout,
        Duration readTimeout,
        Duration apiCallTimeout) {
}
