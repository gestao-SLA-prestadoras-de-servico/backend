package com.gestaosla.backend.identidade.internal;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.auditoria.api.EntidadeAuditada;
import com.gestaosla.backend.auditoria.api.OperacaoAuditada;
import com.gestaosla.backend.cadastro.api.ClienteConsulta;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.identidade.api.NovoUsuarioKeycloak;
import com.gestaosla.backend.identidade.api.UsuarioCorrente;
import com.gestaosla.backend.identidade.api.UsuarioJaExisteException;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RecursoNaoEncontradoException;
import com.gestaosla.backend.shared.api.RegraNegocioException;

import lombok.RequiredArgsConstructor;

/**
 * Cadastro de usuário (RF-09): conta no Keycloak com o papel (RN-127) → registro local + auditoria numa transação →
 * e-mail de definição de senha depois do commit (RN-128). Se a parte local falhar, a conta é removida (RN-136).
 * Sem {@code @Transactional}: a chamada ao Keycloak fica fora da transação do banco.
 */
@Service
@RequiredArgsConstructor
class CadastroUsuarioService {

    private static final Logger log = LoggerFactory.getLogger(CadastroUsuarioService.class);

    private final UsuarioRepository usuarios;
    private final KeycloakAdminClient keycloak;
    private final ClienteConsulta clientes;
    private final AuditoriaService auditoria;
    private final TransactionTemplate transacao;

    record Resultado(Usuario usuario, boolean emailDefinicaoSenhaEnviado) {
    }

    Resultado cadastrar(NovoUsuario dados, UsuarioCorrente gestor) {
        String email = dados.email().trim().toLowerCase(Locale.ROOT);
        validarEmpresa(dados.perfil(), dados.clienteId());
        if (usuarios.existsByEmailIgnoreCase(email)) {
            throw new UsuarioJaExisteException();
        }

        // O e-mail é também o username: conta órfã de cadastro anterior é reaproveitada pelo adaptador.
        UUID sub = keycloak.criarUsuario(new NovoUsuarioKeycloak(email, email, dados.nome().trim()));
        Usuario usuario;
        try {
            keycloak.atribuirPapel(sub, dados.perfil());
            usuario = transacao.execute(t -> gravar(sub, dados, email, gestor));
        } catch (RuntimeException falha) {
            compensar(sub, falha);
            throw falha instanceof DataIntegrityViolationException ? new UsuarioJaExisteException() : falha;
        }
        return new Resultado(usuario, enviarDefinicaoSenha(sub));
    }

    private Usuario gravar(UUID sub, NovoUsuario dados, String email, UsuarioCorrente gestor) {
        Usuario usuario = usuarios.saveAndFlush(new Usuario(sub, dados.nome().trim(), email, dados.perfil(), dados.clienteId()));
        // Sem nome nem e-mail: a trilha é imutável por 5 anos e não deve guardar dado pessoal (RN-122).
        Map<String, Object> detalhes = new HashMap<>();
        detalhes.put("perfil", dados.perfil().name());
        detalhes.put("clienteId", dados.clienteId() == null ? null : dados.clienteId().toString());
        auditoria.registrar(Ator.usuario(gestor.sub(), gestor.papel()), OperacaoAuditada.CADASTRO_USUARIO,
                EntidadeAuditada.USUARIO, usuario.getId(), null, detalhes);
        return usuario;
    }

    private void validarEmpresa(Papel perfil, UUID clienteId) {
        if (perfil != Papel.CLIENTE) {
            if (clienteId != null) {
                throw new RegraNegocioException(CodigoErro.CLIENTE_NAO_PERMITIDO, "Técnico e gestor não pertencem a uma empresa cliente.");
            }
            return;
        }
        if (clienteId == null) {
            throw new RegraNegocioException(CodigoErro.CLIENTE_OBRIGATORIO, "Usuário de perfil CLIENTE precisa da empresa cliente.");
        }
        boolean ativo;
        try {
            ativo = clientes.estaAtivo(clienteId);
        } catch (RecursoNaoEncontradoException naoExiste) {
            throw new RegraNegocioException(CodigoErro.CLIENTE_INEXISTENTE, "Empresa cliente não encontrada.");
        }
        if (!ativo) {
            throw new RegraNegocioException(CodigoErro.CLIENTE_INATIVO, "Empresa cliente inativa não aceita novo usuário.");
        }
    }

    // RN-136. A conta só é removida se nenhum registro local a usa: num cadastro simultâneo da mesma conta órfã,
    // a falha do segundo (sub repetido) não pode apagar a conta que o primeiro acabou de cadastrar.
    private void compensar(UUID sub, RuntimeException falha) {
        try {
            if (!usuarios.existsBySub(sub)) {
                keycloak.remover(sub);
                log.warn("Cadastro desfeito: conta {} removida do Keycloak após falha na gravação local.", sub, falha);
            }
        } catch (RuntimeException naoRemovida) {
            // Fica órfã; um novo cadastro com o mesmo e-mail a reaproveita (autenticação-03).
            log.error("Compensação falhou: conta {} ficou no Keycloak sem registro local.", sub, naoRemovida);
        }
    }

    // Depois do commit: se falhar, o cadastro continua valendo e a resposta avisa.
    private boolean enviarDefinicaoSenha(UUID sub) {
        try {
            keycloak.enviarDefinicaoSenha(sub);
            return true;
        } catch (RuntimeException falha) {
            log.warn("Usuário {} cadastrado, mas o e-mail de definição de senha não foi enviado.", sub, falha);
            return false;
        }
    }
}
