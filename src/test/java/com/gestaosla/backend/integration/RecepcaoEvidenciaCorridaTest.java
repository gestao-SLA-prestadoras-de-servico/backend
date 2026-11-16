package com.gestaosla.backend.integration;

import static com.gestaosla.backend.TokenTeste.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gestaosla.backend.ChamadoTeste;
import com.gestaosla.backend.FotoTeste;
import com.gestaosla.backend.TestcontainersConfiguration;
import com.gestaosla.backend.chamado.api.AtendimentoConsulta;
import com.gestaosla.backend.evidencia.internal.ArmazenamentoTestHelper;

/**
 * A conferência de acesso e estado é refeita dentro da transação: se o chamado muda entre a primeira conferência e a
 * gravação (reatribuição, cancelamento), a evidência não é criada. A mudança é simulada fazendo a segunda consulta do
 * atendimento corrente responder vazio. Classe à parte porque o spy dá a ela um contexto próprio.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RecepcaoEvidenciaCorridaTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext contexto;

    @MockitoSpyBean
    private AtendimentoConsulta atendimentos;

    @Test
    void shouldRecheckAttendanceInsideTheTransactionAndLeaveOrphanObject() throws Exception {
        UUID tecnico = UUID.fromString(ChamadoTeste.usuario(jdbc, "TECNICO", null));
        UUID chamado = ChamadoTeste.chamado(jdbc, ChamadoTeste.cliente(jdbc), "EM_ATENDIMENTO", tecnico);
        jdbc.update("insert into atendimento (id, created_at, updated_at, chamado_id, tecnico_sub, estado, version) "
                + "values (?, now(), now(), ?, ?, 'EM_ANDAMENTO', 0)", UUID.randomUUID(), chamado, tecnico);
        doCallRealMethod().doReturn(Optional.empty()).when(atendimentos).correnteDe(any());

        mvc.perform(multipart("/api/chamados/{id}/evidencias", chamado)
                .file(new MockMultipartFile("arquivo", "foto.jpg", "image/jpeg", FotoTeste.jpegComExif(1, false)))
                .header(HttpHeaders.AUTHORIZATION, token(tecnico.toString(), "TECNICO")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.codigo").value("TRANSICAO_INVALIDA"));

        assertThat(new ArmazenamentoTestHelper(contexto).listar("evidencias/" + chamado + "/")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from evidencia where chamado_id = ?", Long.class, chamado)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where tipo = 'PROCESSAR_EVIDENCIA' and payload->>'chamadoId' = ?",
                Long.class, chamado.toString())).isZero();
    }
}
