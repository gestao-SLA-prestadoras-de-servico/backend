package com.gestaosla.backend.shared.internal;

import java.time.Clock;
import java.time.ZoneId;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Único relógio do sistema (RN-62). Todo código que precisa da hora injeta {@link Clock};
 * a regra ArchUnit {@code RelogioArquiteturaTest} proíbe ler a hora do sistema fora daqui.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    Clock clock(@Value("${app.fuso-horario}") ZoneId fuso) {
        // A JVM segue o mesmo fuso; a sessão JDBC e o Hibernate leem a propriedade no application.properties.
        TimeZone.setDefault(TimeZone.getTimeZone(fuso));
        return Clock.system(fuso);
    }
}
