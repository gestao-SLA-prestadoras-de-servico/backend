package com.gestaosla.backend.integration;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;

import jakarta.persistence.EntityManagerFactory;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;
import java.util.function.BooleanSupplier;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class BackendApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Clock clock;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private MockMvc mvc;

    @Value("${app.fuso-horario}")
    private ZoneId fuso;

    @Test
    void shouldConnectToDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
    }

    @Test
    void shouldCreateChamadoNumeroSequence() {
        Long numero = jdbcTemplate.queryForObject("select nextval('chamado_numero_seq')", Long.class);

        assertThat(numero).isPositive();
    }

    // O ddl-auto=validate aceita timestamp sem fuso para Instant; esta consulta garante o timestamptz.
    @Test
    void shouldStoreEveryDateTimeAsTimestamptz() {
        List<String> semFuso = jdbcTemplate.queryForList("""
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = 'public'
                  and data_type in ('timestamp without time zone', 'time without time zone')
                  and table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(semFuso).isEmpty();
    }

    @Test
    void shouldUseSameTimeZoneInClockJvmJdbcAndHibernate() {
        assertThat(fuso).isEqualTo(ZoneId.of("America/Fortaleza"));
        // "clock" é o bean de produção (ClockConfig); o injetado acima é o RelogioMutavel.
        assertThat(context.getBean("clock", Clock.class).getZone()).isEqualTo(fuso);
        assertThat(TimeZone.getDefault().toZoneId()).isEqualTo(fuso);
        assertThat(jdbcTemplate.queryForObject("select current_setting('TimeZone')", String.class))
                .isEqualTo(fuso.getId());
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.jdbc.time_zone", fuso.getId());
    }

    @Test
    void shouldAdvanceClockAndRunRoutineWithoutWaiting() {
        relogio.definir(Instant.parse("2026-01-05T12:00:00Z"));
        Instant prazo = clock.instant().plus(Duration.ofHours(4));
        BooleanSupplier rotinaPrazoVencido = () -> clock.instant().isAfter(prazo);

        assertThat(rotinaPrazoVencido.getAsBoolean()).isFalse();

        relogio.avancar(Duration.ofHours(4).plusSeconds(1));

        assertThat(rotinaPrazoVencido.getAsBoolean()).isTrue();
    }

    // O 401 nasce no filtro de segurança, fora do MVC; o SegurancaErrosConfig o leva ao GlobalExceptionHandler.
    @Test
    void shouldReturnProblemDetailsWhenNotAuthenticated() throws Exception {
        mvc.perform(get("/qualquer-rota"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("NAO_AUTENTICADO"));
    }
}
