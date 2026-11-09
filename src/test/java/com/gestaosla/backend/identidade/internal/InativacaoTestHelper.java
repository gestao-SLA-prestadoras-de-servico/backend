package com.gestaosla.backend.identidade.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.identidade.api.ChamadosDoTecnico;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.Papel;

/**
 * Monta o InativacaoUsuarioService com repositório e transação simulados, para o teste unitário em {@code unit/} (o CI
 * só roda testes nesse pacote e em {@code integration/}). Expõe só tipos públicos; o serviço e a entidade ficam
 * internos ao módulo. O TransactionManager simulado não faz rollback.
 */
public final class InativacaoTestHelper {

    public record Pessoa(UUID id, UUID sub) {
    }

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final Map<UUID, Usuario> registros = new HashMap<>();
    private final InativacaoUsuarioService servico;

    public InativacaoTestHelper(KeycloakAdminClient keycloak, AuditoriaService auditoria, Optional<ChamadosDoTecnico> chamados) {
        servico = new InativacaoUsuarioService(usuarios, keycloak, auditoria, chamados, mock(PlatformTransactionManager.class));
    }

    public Pessoa usuario(Papel perfil) {
        Usuario usuario = new Usuario(UUID.randomUUID(), "Nome", "n@x.local", perfil, null);
        ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
        registros.put(usuario.getId(), usuario);
        when(usuarios.findById(usuario.getId())).thenReturn(Optional.of(usuario));
        return new Pessoa(usuario.getId(), usuario.getSub());
    }

    public void inativar(UUID id, UUID substitutoId, Ator ator) {
        servico.inativar(id, substitutoId, ator);
    }

    public boolean ativo(UUID id) {
        return registros.get(id).isAtivo();
    }

    /** Quantos gestores ativos o banco "tem" a cada consulta: a da validação inicial e a da transação. */
    public void gestoresAtivos(Long primeira, Long... seguintes) {
        when(usuarios.countByPerfilAndAtivoTrue(Papel.GESTOR)).thenReturn(primeira, seguintes);
    }

    /** Outra inativação do mesmo usuário comita enquanto esta espera a trava. */
    public void inativadoPorOutraTransacaoAoTravar(UUID id) {
        when(usuarios.travarAtivos(Papel.GESTOR)).thenAnswer(chamada -> {
            registros.get(id).inativar();
            return List.of();
        });
    }

    public long travas() {
        return mockingDetails(usuarios).getInvocations().stream()
                .filter(chamada -> chamada.getMethod().getName().equals("travarAtivos")).count();
    }
}
