package com.gestaosla.backend.chamado.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.cadastro.api.ContratoConsulta;
import com.gestaosla.backend.cadastro.api.PoliticaDoContrato;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.sla.api.RelogiosSla;

import lombok.RequiredArgsConstructor;

/**
 * Abertura pelo cliente (RF-12, RF-30). Tudo numa transação (RN-111): se o cálculo dos prazos, a auditoria ou o
 * histórico falhar, o chamado também não existe.
 */
@Service
@RequiredArgsConstructor
class AberturaChamadoService {

    private final ContratoConsulta contratos;
    private final ChamadoRepository chamados;
    private final RelogiosSla relogios;
    private final AuditoriaService auditoria;
    private final HistoricoChamado historico;
    private final Clock clock;

    @Transactional
    Chamado abrir(DadosAbertura dados, UsuarioCorrente cliente) {
        // A empresa vem do usuário autenticado, nunca da requisição (RN-126).
        PoliticaDoContrato versao = contratos.politicaParaAbertura(dados.contratoId(), cliente.empresaClienteId());
        Instant agora = Instant.now(clock);
        Chamado chamado = chamados.saveAndFlush(new Chamado(dados.contratoId(), cliente.empresaClienteId(), versao,
                dados.titulo(), dados.descricao(), dados.local(), dados.prioridadeSugerida(), agora));
        // Prazos pela prioridade padrão da versão: a sugerida não conta (RN-15, RN-16). Fora da janela, começam a
        // correr na próxima abertura (RN-61).
        relogios.iniciarRelogios(chamado.getId(), versao, chamado.prioridadeEfetiva(), agora);
        Ator ator = Ator.usuario(cliente.sub(), cliente.papel());
        Map<String, Object> auditado = Map.of("numero", chamado.getNumero(), "contratoId", dados.contratoId().toString());
        auditoria.registrar(ator, OperacaoAuditada.ABERTURA_CHAMADO, EntidadeAuditada.CHAMADO, chamado.getId(), chamado.getId(), auditado);
        historico.registrar(chamado.getId(), TipoEventoHistorico.ABERTURA, ator, Map.of("numero", chamado.getNumero()));
        return chamado;
    }
}
