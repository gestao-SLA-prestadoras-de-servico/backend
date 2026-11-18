package com.gestaosla.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.gestaosla.backend.EvidenciaRetencaoTeste;
import com.gestaosla.backend.RelogioMutavel;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.evidencia.api.LimpezaOrfaos;
import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RelogioMutavel.Configuracao.class})
class LimpezaOrfaosTest {

    @Autowired
    private ApplicationContext contexto;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LimpezaOrfaos limpeza;

    @Test
    void shouldCleanOlderOrphansAcrossPagesAndProtectRegisteredAndProcessingObjects() {
        var storage = new ArmazenamentoTestHelper(contexto);
        Instant envio = Instant.parse("2026-11-18T12:00:00Z");
        var disponivel = EvidenciaRetencaoTeste.criar(jdbc, "ABERTO", null, "DISPONIVEL", envio, true);
        var recebida = EvidenciaRetencaoTeste.criar(jdbc, "ABERTO", null, "RECEBIDA", envio, false);
        var processando = EvidenciaRetencaoTeste.criar(jdbc, "ABERTO", null, "PROCESSANDO", envio, false);
        var falha = EvidenciaRetencaoTeste.criar(jdbc, "ABERTO", null, "FALHA", envio, false);
        String semRegistro = ArmazenamentoTestHelper.original(UUID.randomUUID(), UUID.randomUUID(), "png");
        String extra = disponivel.prefixo() + "derivado-sem-registro.jpg";
        String chaveInvalida = "chave-sem-evidencia-" + UUID.randomUUID();
        var orfaos = List.of(semRegistro, extra, chaveInvalida);
        var protegidos = List.of(disponivel.original(), disponivel.miniatura(), disponivel.visualizacao(),
                recebida.prefixo() + "auxiliar.jpg", processando.miniatura(), processando.visualizacao(), falha.original());
        for (var chave : orfaos) {
            storage.gravar(chave, new byte[] {1}, "application/octet-stream");
        }
        for (var chave : protegidos) {
            storage.gravar(chave, new byte[] {1}, "application/octet-stream");
        }
        var datas = storage.listar("");
        Instant dataOrfao = datas.get(semRegistro);

        limpeza.executar(dataOrfao.plus(23, ChronoUnit.HOURS), 1, i -> { });
        assertThat(storage.listar("")).containsKey(semRegistro);
        limpeza.executar(dataOrfao.plus(24, ChronoUnit.HOURS), 1, i -> { });
        assertThat(storage.listar("")).containsKey(semRegistro);

        Instant aposPrazo = orfaos.stream().map(datas::get).max(Instant::compareTo).orElseThrow()
                .plus(24, ChronoUnit.HOURS).plusSeconds(1);
        limpeza.executar(aposPrazo, 1, i -> { });
        limpeza.executar(aposPrazo, 1, i -> { });

        assertThat(storage.listar("").keySet()).containsAll(protegidos).doesNotContainAnyElementsOf(orfaos);
    }
}
