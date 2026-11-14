package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.SUB_GESTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.chamado.internal.ChamadoTestHelper;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RegraNegocioException;
import com.gestaosla.backend.sla.api.TipoRelogio;
import com.gestaosla.backend.sla.api.Violacao;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class ChamadoModeloTest {

    private static final Instant AGORA = Instant.parse("2026-11-16T13:00:00Z");
    private static final Ator GESTOR = Ator.usuario(UUID.fromString(SUB_GESTOR), Papel.GESTOR);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext contexto;

    @Autowired
    private RelogioMutavel relogio;

    @Autowired
    private JsonMapper json;

    private ChamadoTestHelper chamados;

    @BeforeEach
    void preparar() {
        chamados = new ChamadoTestHelper(contexto);
        relogio.definir(AGORA);
    }

    // RN-14: o número vem da sequência; é único e crescente.
    @Test
    void shouldNumberChamadosFromSequence() {
        long primeiro = numero(ChamadoTeste.chamado(jdbc));
        long segundo = numero(ChamadoTeste.chamado(jdbc));

        assertThat(segundo).isGreaterThan(primeiro);
    }

    // Toda mudança de estado grava auditoria e item de histórico, na mesma transação.
    @Test
    void shouldAuditAndRecordHistoryOnEveryStateChange() {
        UUID chamado = ChamadoTeste.chamado(jdbc);

        chamados.transicionar(chamado, EstadoChamado.ATRIBUIDO, GESTOR);
        chamados.transicionar(chamado, EstadoChamado.CANCELADO, GESTOR);

        assertThat(estado(chamado)).isEqualTo("CANCELADO");
        assertThat(jdbc.queryForObject("select estado_final_em from chamado where id = ?", Instant.class, chamado)).isEqualTo(AGORA);
        List<Map<String, Object>> auditoria = jdbc.queryForList("""
                select operacao, entidade_tipo, entidade_id, ator_sub, perfil, dados->>'de' de, dados->>'para' para
                from auditoria where chamado_id = ? order by ocorrido_em, id""", chamado);
        assertThat(auditoria).extracting(l -> l.get("operacao"), l -> l.get("entidade_tipo"), l -> l.get("de"), l -> l.get("para"))
                .containsExactly(
                        tuple("MUDANCA_ESTADO", "CHAMADO", "ABERTO", "ATRIBUIDO"),
                        tuple("MUDANCA_ESTADO", "CHAMADO", "ATRIBUIDO", "CANCELADO"));
        assertThat(auditoria.get(0).get("ator_sub")).isEqualTo(UUID.fromString(SUB_GESTOR));
        List<Map<String, Object>> historico = historico(chamado);
        assertThat(historico).extracting(l -> l.get("evento"), l -> l.get("de"), l -> l.get("para"), l -> l.get("visivel_cliente"),
                l -> l.get("visivel_tecnico"), l -> l.get("ator_papel"))
                .containsExactly(
                        tuple("MUDANCA_ESTADO", "ABERTO", "ATRIBUIDO", true, true, "GESTOR"),
                        tuple("MUDANCA_ESTADO", "ATRIBUIDO", "CANCELADO", true, true, "GESTOR"));
    }

    // RN-13: transição inválida é erro de negócio e não grava nada.
    @Test
    void shouldRejectInvalidTransitionWithoutWritingAnything() {
        UUID chamado = ChamadoTeste.chamado(jdbc);

        assertThatThrownBy(() -> chamados.transicionar(chamado, EstadoChamado.RESOLVIDO, GESTOR))
                .isInstanceOfSatisfying(RegraNegocioException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoErro.TRANSICAO_INVALIDA));

        assertThat(estado(chamado)).isEqualTo("ABERTO");
        assertThat(jdbc.queryForObject("select count(*) from auditoria where chamado_id = ?", Long.class, chamado)).isZero();
        assertThat(historico(chamado)).isEmpty();
    }

    // RN-117: outra transação alterou o chamado depois da leitura; a gravação falha com conflito, sem sobrescrever.
    @Test
    void shouldFailWithConflictWhenChamadoChangedConcurrently() {
        UUID chamado = ChamadoTeste.chamado(jdbc);
        Runnable outraTransacao = () -> CompletableFuture.runAsync(
                () -> jdbc.update("update chamado set estado = 'CANCELADO', version = version + 1 where id = ?", chamado)).join();

        assertThatThrownBy(() -> chamados.transicionar(chamado, EstadoChamado.ATRIBUIDO, GESTOR, outraTransacao))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(estado(chamado)).isEqualTo("CANCELADO");
        assertThat(historico(chamado)).isEmpty();
    }

    // RF-58, RN-39, RN-54: um aviso por relógio, a todos os gestores ativos e ao técnico, com eventoId determinístico.
    @Test
    void shouldWriteViolationNoticeToManagersAndTechnician() {
        UUID tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        UUID chamado = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "ATRIBUIDO", tecnico);
        Violacao campo = new Violacao(chamado, TipoRelogio.CAMPO, 1, AGORA.minusSeconds(86_400), AGORA);
        Violacao resposta = new Violacao(chamado, TipoRelogio.RESPOSTA, 1, AGORA.minusSeconds(86_400), AGORA);

        chamados.notificar(chamado, List.of(campo, resposta));

        JsonNode aviso = payload(ChamadoTestHelper.eventoId(campo));
        assertThat(aviso.get("tipo").asString()).isEqualTo("VIOLACAO");
        assertThat(aviso.get("relogio").asString()).isEqualTo("ATENDIMENTO_CAMPO");
        assertThat(aviso.get("chamado").get("id").asString()).isEqualTo(chamado.toString());
        assertThat(aviso.get("chamado").get("numero").asLong()).isEqualTo(numero(chamado));
        assertThat(aviso.get("titulo").asString()).isEqualTo("Ar-condicionado parado");
        assertThat(aviso.get("destinatarios").valueStream().map(JsonNode::asString).toList())
                .contains(SUB_GESTOR, tecnico.toString());
        assertThat(payload(ChamadoTestHelper.eventoId(resposta)).get("relogio").asString()).isEqualTo("RESPOSTA");
        assertThat(jdbc.queryForObject("select routing_key from outbox_eventos where evento_id = ?", String.class,
                ChamadoTestHelper.eventoId(campo))).isEqualTo("interna");
    }

    // RN-54: a mesma violação nunca gera dois avisos; a de outro ciclo (outro marco) gera um novo.
    @Test
    void shouldNotDuplicateSameViolationButNotifyNewCycle() {
        UUID chamado = ChamadoTeste.chamado(jdbc);
        Violacao ciclo1 = new Violacao(chamado, TipoRelogio.RESOLUCAO, 1, AGORA.minusSeconds(86_400), AGORA);
        Violacao ciclo2 = new Violacao(chamado, TipoRelogio.RESOLUCAO, 2, AGORA.minusSeconds(3_600), AGORA);

        chamados.notificar(chamado, List.of(ciclo1));
        chamados.notificar(chamado, List.of(ciclo1, ciclo2));

        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where payload->'chamado'->>'id' = ?", Long.class,
                chamado.toString())).isEqualTo(2);
        assertThat(ChamadoTestHelper.eventoId(ciclo1)).isNotEqualTo(ChamadoTestHelper.eventoId(ciclo2))
                .isEqualTo(ChamadoTestHelper.eventoId(new Violacao(chamado, TipoRelogio.RESOLUCAO, 1, AGORA.minusSeconds(86_400), AGORA)));
    }

    // RN-39: sem técnico atribuído, o aviso vai só aos gestores.
    @Test
    void shouldNotifyOnlyManagersWhenNoTechnicianAssigned() {
        UUID chamado = ChamadoTeste.chamado(jdbc);
        Violacao resposta = new Violacao(chamado, TipoRelogio.RESPOSTA, 1, AGORA.minusSeconds(86_400), AGORA);

        chamados.notificar(chamado, List.of(resposta));

        List<String> destinatarios = payload(ChamadoTestHelper.eventoId(resposta)).get("destinatarios").valueStream()
                .map(JsonNode::asString).toList();
        assertThat(destinatarios).contains(SUB_GESTOR);
        assertThat(jdbc.queryForList("select perfil from usuario where sub::text = any(?)", String.class,
                (Object) destinatarios.toArray(String[]::new))).containsOnly("GESTOR");
    }

    private JsonNode payload(UUID eventoId) {
        return json.readTree(jdbc.queryForObject("select payload::text from outbox_eventos where evento_id = ?", String.class, eventoId));
    }

    private long numero(UUID chamado) {
        return jdbc.queryForObject("select numero from chamado where id = ?", Long.class, chamado);
    }

    private String estado(UUID chamado) {
        return jdbc.queryForObject("select estado from chamado where id = ?", String.class, chamado);
    }

    private List<Map<String, Object>> historico(UUID chamado) {
        return jdbc.queryForList("""
                select evento, ator_papel, visivel_cliente, visivel_tecnico, dados->>'de' de, dados->>'para' para
                from chamado_historico where chamado_id = ? order by ocorrido_em, id""", chamado);
    }
}
