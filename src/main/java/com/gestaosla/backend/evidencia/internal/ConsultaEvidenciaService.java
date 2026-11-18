package com.gestaosla.backend.evidencia.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.gestaosla.backend.chamado.api.AcessoChamado;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;

import lombok.RequiredArgsConstructor;

/** Listagem e escolha do objeto a servir (RF-26, RF-28). Quem não vê o chamado recebe 404 (RN-126). */
@Service
@RequiredArgsConstructor
class ConsultaEvidenciaService {

    /** Minúsculas de propósito: o nome da constante é o valor de {@code ?variante=} e entra no ETag. */
    enum Variante {
        visualizacao, miniatura, original
    }

    /** O objeto a servir, já escolhido, mas ainda não aberto no storage (o 304 dispensa a leitura). */
    record Conteudo(String chave, String tipoConteudo, String etag) {
    }

    private final EvidenciaRepository evidencias;
    private final AcessoChamado acesso;

    List<Evidencia> listar(UUID chamadoId, UsuarioCorrente usuario) {
        if (!acesso.podeAcessar(usuario, chamadoId)) {
            throw new RecursoNaoEncontradoException();
        }
        return evidencias.findByChamadoIdOrderByEnviadaEmAscIdAsc(chamadoId);
    }

    Conteudo localizar(UUID id, Variante pedida, UsuarioCorrente usuario) {
        Evidencia evidencia = evidencias.findById(id).filter(e -> acesso.podeAcessar(usuario, e.getChamadoId()))
                .orElseThrow(RecursoNaoEncontradoException::new);
        if (evidencia.getEstado() == EstadoEvidencia.EXPURGADA) {
            throw new ResponseStatusException(HttpStatus.GONE, "A evidência foi expurgada.");
        }
        String derivado = switch (pedida) {
            case miniatura -> evidencia.getChaveMiniatura();
            case visualizacao -> evidencia.getChaveVisualizacao();
            case original -> null;
        };
        // Derivado ainda não gerado (RECEBIDA, PROCESSANDO ou FALHA): serve o original (RN-81).
        if (derivado == null) {
            return new Conteudo(evidencia.getChaveOriginal(), evidencia.getTipoConteudo(), etag(evidencia, Variante.original));
        }
        return new Conteudo(derivado, "image/jpeg", etag(evidencia, pedida));
    }

    // O hash é do original; a variante distingue os bytes. O conteúdo de uma variante nunca muda.
    private static String etag(Evidencia evidencia, Variante servida) {
        return "\"" + evidencia.getHashSha256() + "-" + servida.name() + "\"";
    }
}
