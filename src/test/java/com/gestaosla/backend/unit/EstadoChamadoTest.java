package com.gestaosla.backend.unit;

import static com.gestaosla.backend.chamado.api.EstadoChamado.ABERTO;
import static com.gestaosla.backend.chamado.api.EstadoChamado.CANCELADO;
import static com.gestaosla.backend.chamado.api.EstadoChamado.EM_ATENDIMENTO;
import static com.gestaosla.backend.chamado.api.EstadoChamado.ENCERRADO;
import static com.gestaosla.backend.chamado.api.EstadoChamado.RESOLVIDO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.cadastro.api.Prazos;
import com.gestaosla.backend.cadastro.api.Prioridade;
import com.gestaosla.backend.chamado.api.EstadoChamado;
import com.gestaosla.backend.chamado.internal.Chamado;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RegraNegocioException;

class EstadoChamadoTest {

    // RN-10: as únicas transições válidas. Qualquer outra combinação é rejeitada (RN-13).
    private static final Set<String> VALIDAS = Set.of(
            "ABERTO>ATRIBUIDO", "ATRIBUIDO>EM_ATENDIMENTO", "EM_ATENDIMENTO>RESOLVIDO", "RESOLVIDO>ENCERRADO",
            "RESOLVIDO>ATRIBUIDO", "EM_ATENDIMENTO>ATRIBUIDO",
            "ABERTO>CANCELADO", "ATRIBUIDO>CANCELADO", "EM_ATENDIMENTO>CANCELADO");

    private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");

    static Stream<Arguments> todasAsCombinacoes() {
        return Arrays.stream(EstadoChamado.values())
                .flatMap(origem -> Arrays.stream(EstadoChamado.values()).map(destino -> Arguments.of(origem, destino)));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("todasAsCombinacoes")
    void shouldAllowOnlyTransitionsOfRn10(EstadoChamado origem, EstadoChamado destino) {
        boolean valida = VALIDAS.contains(origem + ">" + destino);
        Chamado chamado = chamadoEm(origem);

        assertThat(origem.podeIrPara(destino)).isEqualTo(valida);
        if (valida) {
            assertThat(chamado.transicionar(destino, AGORA)).isEqualTo(origem);
            assertThat(chamado.getEstado()).isEqualTo(destino);
        } else {
            assertThatThrownBy(() -> chamado.transicionar(destino, AGORA))
                    .isInstanceOfSatisfying(RegraNegocioException.class,
                            e -> assertThat(e.getCodigo()).isEqualTo(CodigoErro.TRANSICAO_INVALIDA));
            assertThat(chamado.getEstado()).isEqualTo(origem);
        }
    }

    // RN-11: estados finais não aceitam nenhuma transição, inclusive a reabertura.
    @ParameterizedTest
    @EnumSource(names = {"ENCERRADO", "CANCELADO"})
    void shouldRejectEveryTransitionFromFinalStates(EstadoChamado finalizado) {
        assertThat(finalizado.finalizado()).isTrue();
        assertThat(finalizado.destinos()).isEmpty();
    }

    @Test
    void shouldRecordResolutionAndFinalStateInstants() {
        Chamado chamado = chamadoEm(EM_ATENDIMENTO);
        chamado.transicionar(RESOLVIDO, AGORA);
        assertThat(chamado.getResolvidoEm()).isEqualTo(AGORA);
        assertThat(chamado.getEstadoFinalEm()).isNull();

        Instant depois = AGORA.plusSeconds(60);
        chamado.transicionar(ENCERRADO, depois);
        assertThat(chamado.getEstadoFinalEm()).isEqualTo(depois);

        Chamado cancelado = chamadoEm(ABERTO);
        cancelado.transicionar(CANCELADO, AGORA);
        assertThat(cancelado.getEstadoFinalEm()).isEqualTo(AGORA);
        assertThat(cancelado.getResolvidoEm()).isNull();
    }

    // RN-15, RN-16: a sugerida não conta; vale a do gestor ou, sem ela, a padrão da versão.
    @Test
    void shouldUseManagerPriorityOrPolicyDefault() {
        Chamado chamado = new Chamado(UUID.randomUUID(), UUID.randomUUID(), versao(Prioridade.NORMAL), "t", "d", "l",
                Prioridade.ALTA, AGORA);
        assertThat(chamado.getEstado()).isEqualTo(ABERTO);
        assertThat(chamado.prioridadeEfetiva()).isEqualTo(Prioridade.NORMAL);

        ReflectionTestUtils.setField(chamado, "prioridadeDefinida", Prioridade.ALTA);
        assertThat(chamado.prioridadeEfetiva()).isEqualTo(Prioridade.ALTA);
    }

    private static Chamado chamadoEm(EstadoChamado estado) {
        Chamado chamado = new Chamado(UUID.randomUUID(), UUID.randomUUID(), versao(Prioridade.NORMAL), "t", "d", "l", null, AGORA);
        ReflectionTestUtils.setField(chamado, "estado", estado);
        return chamado;
    }

    private static PoliticaDoContrato versao(Prioridade padrao) {
        return new PoliticaDoContrato(UUID.randomUUID(), UUID.randomUUID(), 1, padrao, new Prazos(120, 540, 1080),
                new Prazos(540, 1620, 2700));
    }
}
