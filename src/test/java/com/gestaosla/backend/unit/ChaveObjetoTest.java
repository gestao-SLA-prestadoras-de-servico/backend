package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;

class ChaveObjetoTest {

    private static final UUID CHAMADO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVIDENCIA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String BASE = "evidencias/" + CHAMADO + "/" + EVIDENCIA + "/";

    @Test
    void shouldBuildKeysInTheDocumentedFormat() {
        assertThat(ArmazenamentoTestHelper.prefixo(CHAMADO, EVIDENCIA)).isEqualTo(BASE);
        assertThat(ArmazenamentoTestHelper.original(CHAMADO, EVIDENCIA, "png")).isEqualTo(BASE + "original.png");
        assertThat(ArmazenamentoTestHelper.miniatura(CHAMADO, EVIDENCIA)).isEqualTo(BASE + "miniatura.jpg");
        assertThat(ArmazenamentoTestHelper.visualizacao(CHAMADO, EVIDENCIA)).isEqualTo(BASE + "visualizacao.jpg");
    }
}
