package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;
import com.gestaosla.backend.sla.api.RelogiosSla;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class RotinaViolacaoTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    // Segunda-feira, longe das datas dos outros testes.
    private static final Instant ABERTURA = em("2030-03-04T09:00");
    private static final Instant DEPOIS_DE_TUDO = em("2030-04-01T09:00");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private RelogiosSla relogios;

    @Autowired
    private ContratoConsulta contratos;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private MeterRegistry metricas;

    @Autowired
    private ApplicationContext contexto;

    private ChamadoTestHelper helper;
    private UUID empresa;
    private UUID tecnico;
    private String gestor;

    @BeforeEach
    void preparar() {
        helper = new ChamadoTestHelper(contexto);
        // A rotina varre o banco inteiro: marca antes o que outros testes deixaram vencido, para as contagens serem só
        // deste teste.
        relogio.definir(em("2100-01-01T09:00"));
        helper.rodarRotinaViolacao(100);
        relogio.definir(ABERTURA);
        empresa = ChamadoTeste.cliente(jdbc);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        gestor = ChamadoTeste.usuario(jdbc, "GESTOR", null);
    }

    // RF-33, RN-65, RN-67: cada relógio é marcado quando o prazo passa, com violado_em = vence_em; no vencimento exato,
    // ainda não.
    @Test
    void shouldMarkEachClockWithItsDueTimeAsTimeAdvances() {
        UUID chamado = chamadoComRelogios("ATRIBUIDO", tecnico);
        Map<TipoRelogio, Double> antes = contadores();
        List<Map<String, Object>> porVencimento = jdbc.queryForList(
                "select tipo, vence_em from relogio_sla where chamado_id = ? order by vence_em", chamado);
        List<String> violados = new ArrayList<>();

        for (Map<String, Object> linha : porVencimento) {
            Instant venceEm = ((Timestamp) linha.get("vence_em")).toInstant();
            relogio.definir(venceEm);
            helper.rodarRotinaViolacao(100);
            assertThat(violados(chamado)).containsExactlyElementsOf(violados);

            relogio.definir(venceEm.plusSeconds(60));
            helper.rodarRotinaViolacao(100);
            violados.add((String) linha.get("tipo"));
            assertThat(violados(chamado)).containsExactlyElementsOf(violados);
        }

        assertThat(jdbc.queryForObject("select count(*) from relogio_sla where chamado_id = ? and violado_em = vence_em",
                Long.class, chamado)).isEqualTo(3);
        assertThat(jdbc.queryForList("select dados->>'relogio' from chamado_historico where chamado_id = ? "
                + "and evento = 'VIOLACAO_SLA' and ator_sub is null", String.class, chamado))
                .containsExactlyInAnyOrder("RESPOSTA", "CAMPO", "RESOLUCAO");
        for (TipoRelogio tipo : TipoRelogio.values()) {
            Instant venceEm = jdbc.queryForObject("select vence_em from relogio_sla where chamado_id = ? and tipo = ?",
                    Timestamp.class, chamado, tipo.name()).toInstant();
            UUID eventoId = ChamadoTestHelper.eventoId(new Violacao(chamado, tipo, 1, ABERTURA, venceEm));
            assertThat(destinatarios(eventoId)).contains(tecnico.toString(), gestor);
            assertThat(contadores().get(tipo) - antes.get(tipo)).isEqualTo(1.0);
        }
    }

    // RN-54: a segunda passada não acha nada novo; um aviso por relógio.
    @Test
    void shouldNotNotifyTwiceWhenRunTwice() {
        UUID chamado = chamadoComRelogios("EM_ATENDIMENTO", tecnico);
        relogio.definir(DEPOIS_DE_TUDO);

        helper.rodarRotinaViolacao(100);
        helper.rodarRotinaViolacao(100);

        assertThat(avisos(chamado)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from chamado_historico where chamado_id = ? and evento = 'VIOLACAO_SLA'",
                Long.class, chamado)).isEqualTo(3);
    }

    // RESOLVIDO entra; ENCERRADO e CANCELADO ficam de fora, com os relógios intactos ([chamado - 09]).
    @Test
    void shouldIncludeResolvedAndSkipClosedAndCancelled() {
        UUID resolvido = chamadoComRelogios("RESOLVIDO", tecnico);
        UUID encerrado = chamadoComRelogios("ENCERRADO", tecnico);
        UUID cancelado = chamadoComRelogios("CANCELADO", null);
        relogio.definir(DEPOIS_DE_TUDO);

        helper.rodarRotinaViolacao(100);

        assertThat(violados(resolvido)).hasSize(3);
        assertThat(violados(encerrado)).isEmpty();
        assertThat(violados(cancelado)).isEmpty();
        assertThat(avisos(encerrado) + avisos(cancelado)).isZero();
    }

    // RN-39: sem técnico, só os gestores ativos.
    @Test
    void shouldNotifyOnlyManagersWithoutTechnician() {
        UUID chamado = chamadoComRelogios("ABERTO", null);
        relogio.definir(DEPOIS_DE_TUDO);

        helper.rodarRotinaViolacao(100);

        List<String> para = destinatarios(ChamadoTestHelper.eventoId(
                new Violacao(chamado, TipoRelogio.RESPOSTA, 1, ABERTURA, venceEm(chamado, TipoRelogio.RESPOSTA))));
        assertThat(para).contains(gestor);
        assertThat(para).allSatisfy(sub -> assertThat(jdbc.queryForObject(
                "select perfil from usuario where sub = ?::uuid", String.class, sub)).isEqualTo("GESTOR"));
    }

    // Relógio que outra transação está alterando é pulado e marcado na passada seguinte.
    @Test
    void shouldSkipLockedClockAndMarkItInNextRun() throws Exception {
        UUID chamado = chamadoComRelogios("ATRIBUIDO", tecnico);
        relogio.definir(DEPOIS_DE_TUDO);
        CountDownLatch travado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        CompletableFuture<Void> usuario = CompletableFuture.runAsync(() -> transacao.executeWithoutResult(s -> {
            jdbc.queryForList("select id from relogio_sla where chamado_id = ? and tipo = 'RESPOSTA' for update", chamado);
            travado.countDown();
            aguardar(liberar);
        }));
        assertThat(travado.await(10, TimeUnit.SECONDS)).isTrue();

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> helper.rodarRotinaViolacao(100));
        assertThat(violados(chamado)).containsExactlyInAnyOrder("CAMPO", "RESOLUCAO");

        liberar.countDown();
        usuario.get(10, TimeUnit.SECONDS);
        helper.rodarRotinaViolacao(100);
        assertThat(violados(chamado)).containsExactlyInAnyOrder("RESPOSTA", "CAMPO", "RESOLUCAO");
    }

    // RN-119: lotes pequenos, passando por cima dos relógios de um chamado cancelado sem voltar a eles.
    @Test
    void shouldProcessAllClocksInBatchesSkippingFinalizedOnes() {
        UUID cancelado = chamadoComRelogios("CANCELADO", null);
        List<UUID> abertos = List.of(chamadoComRelogios("ATRIBUIDO", tecnico), chamadoComRelogios("ABERTO", null),
                chamadoComRelogios("RESOLVIDO", tecnico));
        relogio.definir(DEPOIS_DE_TUDO);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> helper.rodarRotinaViolacao(2));

        assertThat(abertos).allSatisfy(c -> assertThat(violados(c)).hasSize(3));
        assertThat(violados(cancelado)).isEmpty();
    }

    private UUID chamadoComRelogios(String estado, UUID tecnicoSub) {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, estado, tecnicoSub);
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, ABERTURA));
        return chamado;
    }

    /** Tipos violados, em ordem de vencimento. */
    private List<String> violados(UUID chamado) {
        return jdbc.queryForList("select tipo from relogio_sla where chamado_id = ? and violado_em is not null order by vence_em",
                String.class, chamado);
    }

    private long avisos(UUID chamado) {
        return jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'VIOLACAO' and payload->'chamado'->>'id' = ?",
                Long.class, chamado.toString());
    }

    private List<String> destinatarios(UUID eventoId) {
        return jdbc.queryForList("select jsonb_array_elements_text(payload->'destinatarios') from outbox_eventos where evento_id = ?",
                String.class, eventoId);
    }

    private Instant venceEm(UUID chamado, TipoRelogio tipo) {
        return jdbc.queryForObject("select vence_em from relogio_sla where chamado_id = ? and tipo = ?", Timestamp.class,
                chamado, tipo.name()).toInstant();
    }

    private Map<TipoRelogio, Double> contadores() {
        Map<TipoRelogio, Double> valores = new EnumMap<>(TipoRelogio.class);
        for (TipoRelogio tipo : TipoRelogio.values()) {
            Counter contador = metricas.find("sla.violacoes").tag("relogio", tipo.name()).counter();
            valores.put(tipo, contador == null ? 0.0 : contador.count());
        }
        return valores;
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Instant em(String dataHora) {
        return LocalDateTime.parse(dataHora).atZone(FORTALEZA).toInstant();
    }
}
