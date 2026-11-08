package com.gestaosla.backend.identidade.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.identidade.api.ChamadosDoTecnico;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;

/**
 * Inativação de usuário (RF-10, RN-132): valida → desabilita a conta no Keycloak → transação local (inativo +
 * transferência + auditoria). Se a parte local falhar, a conta é reabilitada (RN-136). Sem {@code @Transactional}: a
 * chamada ao Keycloak fica fora da transação do banco.
 */
@Service
class InativacaoUsuarioService {

    private static final Logger log = LoggerFactory.getLogger(InativacaoUsuarioService.class);

    private final UsuarioRepository usuarios;
    private final KeycloakAdminClient keycloak;
    private final AuditoriaService auditoria;
    private final Optional<ChamadosDoTecnico> chamados; // Vazio até a [chamado - 13]: sem o módulo, não há chamados.
    private final TransactionTemplate transacao;

    InativacaoUsuarioService(UsuarioRepository usuarios, KeycloakAdminClient keycloak, AuditoriaService auditoria,
            Optional<ChamadosDoTecnico> chamados, PlatformTransactionManager transacoes) {
        this.usuarios = usuarios;
        this.keycloak = keycloak;
        this.auditoria = auditoria;
        this.chamados = chamados;
        this.transacao = new TransactionTemplate(transacoes);
        // O ouvinte de ClienteInativado roda depois do commit: sem isto, entraria na transação encerrada e nada gravaria.
        this.transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Idempotente: usuário já inativo não repete a chamada ao Keycloak nem a auditoria. */
    void inativar(UUID id, UUID substitutoId, Ator ator) {
        Usuario usuario = buscar(id);
        if (!usuario.isAtivo()) {
            return;
        }
        if (usuario.getSub().equals(ator.sub())) {
            throw new RegraNegocioException(CodigoErro.AUTOINATIVACAO_NAO_PERMITIDA, "O gestor não pode inativar a si mesmo.");
        }
        validar(usuario, substitutoId);

        keycloak.desabilitar(usuario.getSub());
        try {
            transacao.executeWithoutResult(t -> gravar(id, substitutoId, ator));
        } catch (RuntimeException falha) {
            reabilitar(id, usuario.getSub(), falha);
            throw falha;
        }
    }

    private void gravar(UUID id, UUID substitutoId, Ator ator) {
        // ponytail: trava global nas linhas dos gestores; inativação é rara. Serializa as inativações e, com isso, a
        // revalidação: dois gestores inativando um ao outro, ou dois técnicos substitutos um do outro, passariam juntos.
        usuarios.travarAtivos(Papel.GESTOR);
        Usuario usuario = buscar(id);
        if (!usuario.isAtivo()) {
            return;
        }
        UUID substitutoSub = validar(usuario, substitutoId);
        usuario.inativar();
        if (usuario.getPerfil() == Papel.TECNICO && substitutoSub != null) {
            chamados.ifPresent(c -> c.transferir(usuario.getSub(), substitutoSub, ator));
        }
        // Sem nome nem e-mail: a trilha é imutável por 5 anos e não deve guardar dado pessoal (RN-122).
        Map<String, Object> detalhes = new HashMap<>();
        detalhes.put("perfil", usuario.getPerfil().name());
        detalhes.put("substitutoId", substitutoId == null ? null : substitutoId.toString());
        auditoria.registrar(ator, OperacaoAuditada.INATIVACAO_USUARIO, EntidadeAuditada.USUARIO, id, null, detalhes);
    }

    private UUID validar(Usuario usuario, UUID substitutoId) {
        if (usuario.getPerfil() == Papel.GESTOR && usuarios.countByPerfilAndAtivoTrue(Papel.GESTOR) <= 1) {
            throw new RegraNegocioException(CodigoErro.ULTIMO_GESTOR_ATIVO, "O último gestor ativo não pode ser inativado.");
        }
        UUID substitutoSub = substituto(usuario, substitutoId);
        if (usuario.getPerfil() == Papel.TECNICO && substitutoSub == null && naoFinalizados(usuario) > 0) {
            throw new RegraNegocioException(CodigoErro.SUBSTITUTO_OBRIGATORIO, "Técnico com chamados não finalizados exige um substituto.");
        }
        return substitutoSub;
    }

    private UUID substituto(Usuario usuario, UUID substitutoId) {
        if (substitutoId == null) {
            return null;
        }
        return usuarios.findById(substitutoId)
                .filter(s -> !s.getId().equals(usuario.getId()) && s.isAtivo() && s.getPerfil() == Papel.TECNICO)
                .map(Usuario::getSub)
                .orElseThrow(() -> new RegraNegocioException(CodigoErro.SUBSTITUTO_INVALIDO, "O substituto precisa ser outro técnico ativo."));
    }

    private long naoFinalizados(Usuario usuario) {
        return chamados.map(c -> c.contarNaoFinalizados(usuario.getSub())).orElse(0L);
    }

    // RN-136. Só reabilita se o registro continua ativo: a falha pode ser de uma inativação concorrente que já valeu.
    private void reabilitar(UUID id, UUID sub, RuntimeException falha) {
        try {
            if (usuarios.findById(id).filter(Usuario::isAtivo).isPresent()) {
                keycloak.reabilitar(sub);
                log.warn("Inativação desfeita: conta {} reabilitada no Keycloak após falha na gravação local.", sub, falha);
            }
        } catch (RuntimeException naoReabilitada) {
            // Conta desabilitada com registro ativo: o usuário não loga até repetirem a inativação ou reabilitarem à mão.
            log.error("Compensação falhou: conta {} ficou desabilitada no Keycloak com registro local ativo.", sub, naoReabilitada);
        }
    }

    private Usuario buscar(UUID id) {
        return usuarios.findById(id).orElseThrow(RecursoNaoEncontradoException::new);
    }
}
