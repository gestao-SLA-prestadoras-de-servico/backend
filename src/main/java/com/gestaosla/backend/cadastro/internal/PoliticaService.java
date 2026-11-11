package com.gestaosla.backend.cadastro.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.ConflitoException;
import com.gestaosla.backend.shared.api.Paginas;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class PoliticaService {

    private final PoliticaSlaRepository politicas;
    private final VersaoPoliticaRepository versoes;
    private final AuditoriaService auditoria;
    private final Clock clock;

    /** Política com a versão 1. */
    @Transactional
    PoliticaComVersao criar(DadosPolitica dados, Ator gestor) {
        dados.prazos().validarOrdem();
        // A UNIQUE do banco cobre a corrida entre dois cadastros simultâneos; aqui o caso comum ganha o código certo.
        if (politicas.existsByNome(dados.nome())) {
            throw new ConflitoException(CodigoErro.NOME_POLITICA_JA_EXISTE, "Já existe política com este nome.");
        }
        PoliticaSla politica = politicas.save(new PoliticaSla(dados.nome()));
        return new PoliticaComVersao(politica, gravarVersao(politica, 1, dados.versao(), gestor));
    }

    /** Alterar a política é criar uma versão nova; as anteriores não mudam (RN-04). */
    @Transactional
    VersaoPolitica novaVersao(UUID politicaId, DadosVersao dados, Ator gestor) {
        dados.prazos().validarOrdem();
        PoliticaSla politica = politicas.buscarParaVersionar(politicaId).orElseThrow(RecursoNaoEncontradoException::new);
        int numero = versoes.findFirstByPoliticaIdOrderByNumeroDesc(politicaId).map(VersaoPolitica::getNumero).orElse(0) + 1;
        return gravarVersao(politica, numero, dados, gestor);
    }

    @Transactional(readOnly = true)
    Page<PoliticaComVersao> listar(Pageable pagina) {
        Page<PoliticaSla> encontradas = politicas.findAll(Paginas.comDesempate(pagina));
        Map<UUID, VersaoPolitica> atuais = versoes.atuais(encontradas.map(PoliticaSla::getId).getContent()).stream()
                .collect(Collectors.toMap(VersaoPolitica::getPoliticaId, Function.identity()));
        return encontradas.map(p -> new PoliticaComVersao(p, atuais.get(p.getId())));
    }

    @Transactional(readOnly = true)
    Page<VersaoPolitica> versoes(UUID politicaId, Pageable pagina) {
        if (!politicas.existsById(politicaId)) {
            throw new RecursoNaoEncontradoException();
        }
        return versoes.findByPoliticaId(politicaId, Paginas.comDesempate(pagina));
    }

    // RN-121: cada versão nova, inclusive a primeira, é uma alteração de política auditada na mesma transação.
    private VersaoPolitica gravarVersao(PoliticaSla politica, int numero, DadosVersao dados, Ator gestor) {
        VersaoPolitica versao = versoes.save(new VersaoPolitica(politica.getId(), numero, dados, Instant.now(clock), gestor.sub()));
        auditoria.registrar(gestor, OperacaoAuditada.ALTERACAO_POLITICA, EntidadeAuditada.POLITICA_SLA, politica.getId(), null,
                Map.of("versao", numero, "versaoId", versao.getId().toString()));
        return versao;
    }

    record PoliticaComVersao(PoliticaSla politica, VersaoPolitica versaoAtual) {
    }
}
