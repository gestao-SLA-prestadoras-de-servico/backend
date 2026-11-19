package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.sla.api.RelogiosSla;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ValidacaoAutomaticaTest {

    private static final ZoneId FORTALEZA = ZoneId.of("America/Fortaleza");
    private static final Instant ABERTURA = em("2030-03-11T09:00");
    // Segunda 10:00: aviso na quarta 10:00 (1080 min de janela), encerramento na quinta 10:00 (1620 min).
    private static final Instant RESOLUCAO = em("2030-03-11T10:00");
    private static final Instant AVISO = em("2030-03-13T10:00");
    private static final Instant LIMITE = em("2030-03-14T10:00");

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
    private ApplicationContext contexto;

    private ChamadoTestHelper helper;
    private UUID empresa;
    private UUID tecnico;

    @BeforeEach
    void preparar() {
        helper = new ChamadoTestHelper(contexto);
        relogio.definir(RESOLUCAO);
        empresa = ChamadoTeste.cliente(jdbc);
        tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
    }

    // RN-34, RN-92, RF-35: com execução de hora em hora, aviso na quarta ~10:00 e encerramento na quinta ~10:00; o
    // aviso sai uma vez só.
    @Test
    void shouldWarnOnWednesdayAndCloseOnThursdayRunningHourly() {
        UUID chamado = resolvido(RESOLUCAO);

        for (Instant hora = RESOLUCAO.plusSeconds(3600); !hora.isAfter(em("2030-03-15T12:00")); hora = hora.plusSeconds(3600)) {
            relogio.definir(hora);
            helper.rodarValidacaoAutomatica(100);
        }

        assertThat(instante("aviso_enviado_em", chamado)).isEqualTo(AVISO);
        assertThat(instante("estado_final_em", chamado)).isEqualTo(LIMITE);
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'AVISO_ENCERRAMENTO' "
                + "and payload->'chamado'->>'id' = ?", Long.class, chamado.toString())).isEqualTo(1);
        UUID eventoId = ChamadoTestHelper.eventoIdAvisoEncerramento(chamado, RESOLUCAO);
        assertThat(jdbc.queryForObject("select payload->>'encerramentoPrevistoEm' from outbox_eventos where evento_id = ?",
                String.class, eventoId)).isEqualTo(LIMITE.toString());
        assertThat(jdbc.queryForObject("select payload->'destinatarios'->>0 from outbox_eventos where evento_id = ?",
                String.class, eventoId)).isEqualTo("c@x.com");
    }

    // RN-121, RN-68: ENCERRADO com estado_final_em, apuração congelada e auditoria de validação automática pelo SISTEMA.
    @Test
    void shouldCloseFreezingAssessmentAndAuditAsSystem() {
        UUID chamado = resolvido(RESOLUCAO);
        jdbc.update("update chamado set aviso_enviado_em = ? where id = ?", Timestamp.from(AVISO), chamado);
        relogio.definir(LIMITE);

        helper.rodarValidacaoAutomatica(100);

        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("ENCERRADO");
        assertThat(instante("estado_final_em", chamado)).isEqualTo(LIMITE);
        assertThat(jdbc.queryForObject("select count(*) from apuracao_sla where chamado_id = ?", Long.class, chamado)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from auditoria where chamado_id = ? and operacao = 'VALIDACAO_AUTOMATICA' "
                + "and ator_sub is null and perfil is null", Long.class, chamado)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from auditoria where chamado_id = ? and operacao = 'MUDANCA_ESTADO' "
                + "and dados->>'para' = 'ENCERRADO' and ator_sub is null", Long.class, chamado)).isEqualTo(1);
    }

    // Um minuto antes de cada marco, nada acontece.
    @Test
    void shouldDoNothingBeforeTheMarks() {
        UUID chamado = resolvido(RESOLUCAO);
        relogio.definir(AVISO.minusSeconds(60));
        helper.rodarValidacaoAutomatica(100);
        assertThat(instante("aviso_enviado_em", chamado)).isNull();

        relogio.definir(AVISO);
        helper.rodarValidacaoAutomatica(100);
        relogio.definir(LIMITE.minusSeconds(60));
        helper.rodarValidacaoAutomatica(100);

        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("RESOLVIDO");
    }

    // RN-93: reaberto e resolvido de novo, o chamado ganha outro aviso, com outro eventoId.
    @Test
    void shouldSendNewWarningAfterReopeningAndNewResolution() {
        UUID chamado = resolvido(RESOLUCAO);
        relogio.definir(AVISO);
        helper.rodarValidacaoAutomatica(100);

        Instant novaResolucao = em("2030-03-13T15:00");
        relogio.definir(novaResolucao.minusSeconds(3600));
        helper.transicionar(chamado, EstadoChamado.ATRIBUIDO, Ator.SISTEMA);
        helper.transicionar(chamado, EstadoChamado.EM_ATENDIMENTO, Ator.SISTEMA);
        relogio.definir(novaResolucao);
        helper.transicionar(chamado, EstadoChamado.RESOLVIDO, Ator.SISTEMA);
        assertThat(instante("aviso_enviado_em", chamado)).isNull();

        relogio.definir(em("2030-03-15T15:00"));
        helper.rodarValidacaoAutomatica(100);

        assertThat(jdbc.queryForList("select evento_id from outbox_eventos where tipo = 'AVISO_ENCERRAMENTO' "
                + "and payload->'chamado'->>'id' = ? order by evento_id", UUID.class, chamado.toString()))
                .containsExactlyInAnyOrder(ChamadoTestHelper.eventoIdAvisoEncerramento(chamado, RESOLUCAO),
                        ChamadoTestHelper.eventoIdAvisoEncerramento(chamado, novaResolucao));
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("RESOLVIDO");
    }

    // Decisão do usuário: aviso atrasado (sistema fora) sai na hora e adia o encerramento para 1 dia útil depois dele.
    @Test
    void shouldPostponeClosingWhenWarningIsLate() {
        UUID chamado = resolvido(RESOLUCAO);
        Instant atrasado = em("2030-03-14T11:00");
        Instant adiado = em("2030-03-15T11:00");

        relogio.definir(atrasado);
        helper.rodarValidacaoAutomatica(100);
        assertThat(instante("aviso_enviado_em", chamado)).isEqualTo(atrasado);
        assertThat(jdbc.queryForObject("select payload->>'encerramentoPrevistoEm' from outbox_eventos where evento_id = ?",
                String.class, ChamadoTestHelper.eventoIdAvisoEncerramento(chamado, RESOLUCAO))).isEqualTo(adiado.toString());

        relogio.definir(adiado.minusSeconds(60));
        helper.rodarValidacaoAutomatica(100);
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("RESOLVIDO");

        relogio.definir(adiado);
        helper.rodarValidacaoAutomatica(100);
        assertThat(instante("estado_final_em", chamado)).isEqualTo(adiado);
    }

    // Chamado que o cliente está validando ou reabrindo (linha travada) é pulado e volta na passada seguinte.
    @Test
    void shouldSkipLockedTicketAndCloseItInNextRun() throws Exception {
        UUID chamado = resolvido(RESOLUCAO);
        jdbc.update("update chamado set aviso_enviado_em = ? where id = ?", Timestamp.from(AVISO), chamado);
        relogio.definir(LIMITE);
        CountDownLatch travado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        CompletableFuture<Void> cliente = CompletableFuture.runAsync(() -> transacao.executeWithoutResult(s -> {
            jdbc.queryForList("select id from chamado where id = ? for update", chamado);
            travado.countDown();
            aguardar(liberar);
        }));
        assertThat(travado.await(10, TimeUnit.SECONDS)).isTrue();

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> helper.rodarValidacaoAutomatica(100));
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("RESOLVIDO");

        liberar.countDown();
        cliente.get(10, TimeUnit.SECONDS);
        helper.rodarValidacaoAutomatica(100);
        assertThat(jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado)).isEqualTo("ENCERRADO");
    }

    // RN-119: lotes pequenos, passando pelos resolvidos que ainda não chegaram ao aviso sem voltar a eles.
    @Test
    void shouldProcessAllTicketsInBatches() {
        List<UUID> devidos = List.of(resolvido(RESOLUCAO), resolvido(RESOLUCAO.plusSeconds(60)), resolvido(RESOLUCAO.plusSeconds(120)));
        List<UUID> recentes = List.of(resolvido(AVISO), resolvido(AVISO.plusSeconds(60)));
        relogio.definir(em("2030-03-13T11:00"));

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> helper.rodarValidacaoAutomatica(2));

        assertThat(devidos).allSatisfy(c -> assertThat(instante("aviso_enviado_em", c)).isNotNull());
        assertThat(recentes).allSatisfy(c -> assertThat(instante("aviso_enviado_em", c)).isNull());
    }

    /** RESOLVIDO no instante dado, com os três relógios (campo e resolução cumpridos). */
    private UUID resolvido(Instant resolvidoEm) {
        UUID chamado = ChamadoTeste.chamado(jdbc, empresa, "RESOLVIDO", tecnico);
        transacao.executeWithoutResult(s -> relogios.iniciarRelogios(chamado,
                contratos.versaoDaPolitica(ChamadoTeste.VERSAO_PADRAO), Prioridade.NORMAL, ABERTURA));
        jdbc.update("update relogio_sla set cumprido_em = ? where chamado_id = ?", Timestamp.from(resolvidoEm), chamado);
        jdbc.update("update chamado set resolvido_em = ? where id = ?", Timestamp.from(resolvidoEm), chamado);
        return chamado;
    }

    private Instant instante(String coluna, UUID chamado) {
        Timestamp valor = jdbc.queryForObject("select " + coluna + " from chamado where id = ?", Timestamp.class, chamado);
        return valor == null ? null : valor.toInstant();
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
