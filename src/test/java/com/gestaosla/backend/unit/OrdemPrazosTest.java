package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import com.gestaosla.backend.cadastro.api.Prazos;
import com.gestaosla.backend.cadastro.internal.PrazosPorPrioridade;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RegraNegocioException;

class OrdemPrazosTest {

    private static final Prazos ALTA = new Prazos(120, 540, 1080);
    private static final Prazos NORMAL = new Prazos(540, 1620, 2700);

    @Test
    void shouldAcceptDefaultPolicyMatrix() {
        assertThatCode(() -> new PrazosPorPrioridade(ALTA, NORMAL).validarOrdem()).doesNotThrowAnyException();
    }

    // RN-30: igualdade também viola (é "menor que", não "menor ou igual").
    @ParameterizedTest
    @CsvSource({
            "540, 540, 1080",   // resposta = campo
            "600, 540, 1080",   // resposta > campo
            "120, 1080, 1080",  // campo = resolução
            "120, 2000, 1080",  // campo > resolução
            "2000, 540, 100"})  // tudo invertido
    void shouldRejectOutOfOrderDeadlinesInEachPriority(int resposta, int campo, int resolucao) {
        Prazos errado = new Prazos(resposta, campo, resolucao);

        assertThatThrownBy(() -> new PrazosPorPrioridade(errado, NORMAL).validarOrdem())
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("ALTA")
                .extracting("codigo").isEqualTo(CodigoErro.ORDEM_PRAZOS_INVALIDA);
        assertThatThrownBy(() -> new PrazosPorPrioridade(ALTA, errado).validarOrdem())
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("NORMAL")
                .extracting("codigo").isEqualTo(CodigoErro.ORDEM_PRAZOS_INVALIDA);
    }
}
