package com.gestaosla.backend.evidencia.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.chamado.api.AcessoChamado;
import com.gestaosla.backend.chamado.api.AtendimentoConsulta;
import com.gestaosla.backend.chamado.api.AtendimentoCorrente;
import com.gestaosla.backend.chamado.api.EstadoAtendimento;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.outbox.api.EventoSaida;
import com.gestaosla.backend.outbox.api.OutboxService;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;

/**
 * Recepção da evidência (RF-24, RF-25, RN-83): confere quem envia, sanitiza, grava o objeto no storage e só então abre a
 * transação com a evidência RECEBIDA, a auditoria e o evento de processamento (RN-112, RN-113). Se a transação falhar, o
 * objeto fica órfão no storage, para a rotina de retenção remover.
 */
@Service
class RecepcaoEvidenciaService {

    static final long LIMITE_BYTES = 10L * 1024 * 1024;
    private static final int TAMANHO_NOME = 255;

    private final AcessoChamado acesso;
    private final AtendimentoConsulta atendimentos;
    private final SanitizadorImagem sanitizador;
    private final ArmazenamentoEvidencias armazenamento;
    private final EvidenciaRepository evidencias;
    private final AuditoriaService auditoria;
    private final OutboxService outbox;
    private final TransactionTemplate transacao;
    private final Clock clock;

    RecepcaoEvidenciaService(AcessoChamado acesso, AtendimentoConsulta atendimentos, SanitizadorImagem sanitizador,
            ArmazenamentoEvidencias armazenamento, EvidenciaRepository evidencias, AuditoriaService auditoria,
            OutboxService outbox, PlatformTransactionManager transacoes, Clock clock) {
        this.acesso = acesso;
        this.atendimentos = atendimentos;
        this.sanitizador = sanitizador;
        this.armazenamento = armazenamento;
        this.evidencias = evidencias;
        this.auditoria = auditoria;
        this.outbox = outbox;
        this.transacao = new TransactionTemplate(transacoes);
        this.clock = clock;
    }

    UUID receber(UUID chamadoId, MultipartFile arquivo, UsuarioCorrente usuario) {
        // Quem não pode enviar não gasta CPU nem rede: a conferência vem antes de ler o arquivo.
        atendimentoDoEnvio(chamadoId, usuario);
        if (arquivo.getSize() > LIMITE_BYTES) {
            throw new MaxUploadSizeExceededException(LIMITE_BYTES);
        }
        byte[] bruto;
        try {
            bruto = arquivo.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SanitizadorImagem.Resultado imagem = sanitizador.sanitizar(bruto);
        String hash = sha256(imagem.bytes());
        UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        String chave = new ChaveObjeto(chamadoId, id).original(imagem.extensao());
        armazenamento.gravar(chave, new ByteArrayInputStream(imagem.bytes()), imagem.bytes().length, imagem.tipoConteudo());

        transacao.executeWithoutResult(s -> {
            // Refeita aqui: entre a primeira conferência e a gravação o chamado pode ter sido reatribuído ou cancelado.
            AtendimentoCorrente atendimento = atendimentoDoEnvio(chamadoId, usuario);
            evidencias.save(new Evidencia(id, atendimento.id(), chamadoId, nome(arquivo), imagem.tipoConteudo(),
                    imagem.bytes().length, hash, chave, Instant.now(clock)));
            // Sem o nome do arquivo: pode conter dado pessoal (RN-122).
            auditoria.registrar(Ator.usuario(usuario.sub(), usuario.papel()), OperacaoAuditada.ENVIO_EVIDENCIA,
                    EntidadeAuditada.EVIDENCIA, id, chamadoId,
                    Map.of("tipoConteudo", imagem.tipoConteudo(), "tamanho", imagem.bytes().length, "hashSha256", hash));
            outbox.registrar(eventoProcessamento(id, chamadoId, chave));
        });
        return id;
    }

    /** Só a referência ao objeto, nunca o binário (RN-85). Também usado pelo reprocessamento. */
    static EventoSaida eventoProcessamento(UUID evidenciaId, UUID chamadoId, String chaveOriginal) {
        return new EventoSaida(null, "PROCESSAR_EVIDENCIA", "evidencias", List.of("processamento"),
                Map.of("evidenciaId", evidenciaId.toString(), "chamadoId", chamadoId.toString(), "chaveOriginal", chaveOriginal));
    }

    /**
     * Técnico atribuído (senão 404, RN-126) com o atendimento em andamento (senão 422). Atendimento EM_ANDAMENTO implica
     * chamado EM_ATENDIMENTO: o início muda os dois juntos, e reatribuição e cancelamento interrompem o atendimento.
     */
    private AtendimentoCorrente atendimentoDoEnvio(UUID chamadoId, UsuarioCorrente usuario) {
        if (!acesso.podeAcessar(usuario, chamadoId)) {
            throw new RecursoNaoEncontradoException();
        }
        return atendimentos.correnteDe(chamadoId).filter(a -> a.estado() == EstadoAtendimento.EM_ANDAMENTO)
                .orElseThrow(() -> new RegraNegocioException(CodigoErro.TRANSICAO_INVALIDA,
                        "O chamado não tem atendimento em andamento."));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Só informativo: sem path (alguns clientes enviam o caminho completo) e truncado. */
    private static String nome(MultipartFile arquivo) {
        String nome = arquivo.getOriginalFilename();
        if (nome == null || nome.isBlank()) {
            return null;
        }
        nome = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1);
        return nome.length() > TAMANHO_NOME ? nome.substring(0, TAMANHO_NOME) : nome;
    }
}
