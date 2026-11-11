package com.gestaosla.backend.identidade.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.identidade.api.ChamadosDoTecnico;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RegraNegocioException;

/** Regras e ordem do fluxo, com os colaboradores simulados. O fluxo real está em InativacaoUsuarioTest. */
class InativacaoUsuarioServiceTest {

    private static final Ator GESTOR = Ator.usuario(UUID.randomUUID(), Papel.GESTOR);

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final AuditoriaService auditoria = mock(AuditoriaService.class);
    private final ChamadosDoTecnico chamados = mock(ChamadosDoTecnico.class);
    private final InativacaoUsuarioService servico = new InativacaoUsuarioService(usuarios, keycloak, auditoria, Optional.of(chamados),
            mock(PlatformTransactionManager.class));

    @Test
    void shouldRejectLastActiveManagerBeforeCallingKeycloak() {
        Usuario gestor = usuario(Papel.GESTOR);
        when(usuarios.countByPerfilAndAtivoTrue(Papel.GESTOR)).thenReturn(1L);

        assertRegra(gestor, null, CodigoErro.ULTIMO_GESTOR_ATIVO);

        verifyNoInteractions(keycloak);
    }

    // Dois gestores inativando um ao outro: a checagem inicial passa para os dois; a da transação, depois da trava, barra.
    @Test
    void shouldRecheckLastManagerInsideTransactionAndReenableAccount() {
        Usuario gestor = usuario(Papel.GESTOR);
        when(usuarios.countByPerfilAndAtivoTrue(Papel.GESTOR)).thenReturn(2L, 1L);

        assertRegra(gestor, null, CodigoErro.ULTIMO_GESTOR_ATIVO);

        InOrder ordem = inOrder(usuarios, keycloak);
        ordem.verify(keycloak).desabilitar(gestor.getSub());
        ordem.verify(usuarios).travarAtivos(Papel.GESTOR);
        ordem.verify(keycloak).reabilitar(gestor.getSub());
        assertThat(gestor.isAtivo()).isTrue();
    }

    @Test
    void shouldRequireSubstituteBeforeCallingKeycloak() {
        Usuario tecnico = usuario(Papel.TECNICO);
        when(chamados.contarNaoFinalizados(tecnico.getSub())).thenReturn(1L);

        assertRegra(tecnico, null, CodigoErro.SUBSTITUTO_OBRIGATORIO);

        verifyNoInteractions(keycloak);
    }

    // Chamado atribuído entre a validação e a transação.
    @Test
    void shouldRecheckOpenTicketsInsideTransaction() {
        Usuario tecnico = usuario(Papel.TECNICO);
        when(chamados.contarNaoFinalizados(tecnico.getSub())).thenReturn(0L, 1L);

        assertRegra(tecnico, null, CodigoErro.SUBSTITUTO_OBRIGATORIO);

        verify(keycloak).reabilitar(tecnico.getSub());
        verify(auditoria, never()).registrar(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldInactivateTechnicianWhileChamadoModuleDoesNotExist() {
        Usuario tecnico = usuario(Papel.TECNICO);
        InativacaoUsuarioService semModulo = new InativacaoUsuarioService(usuarios, keycloak, auditoria, Optional.empty(),
                mock(PlatformTransactionManager.class));

        semModulo.inativar(tecnico.getId(), null, GESTOR);

        assertThat(tecnico.isAtivo()).isFalse();
    }

    // Outra inativação já valeu: reabilitar deixaria a conta aberta com o registro inativo.
    @Test
    void shouldNotReenableAccountWhenRecordIsAlreadyInactive() {
        Usuario tecnico = usuario(Papel.TECNICO);
        doThrow(new IllegalStateException("falha na auditoria")).when(auditoria).registrar(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> servico.inativar(tecnico.getId(), null, GESTOR)).isInstanceOf(IllegalStateException.class);

        // O mock não faz rollback: o registro segue inativo, como depois de um commit concorrente.
        assertThat(tecnico.isAtivo()).isFalse();
        verify(keycloak, never()).reabilitar(any());
    }

    @Test
    void shouldKeepOriginalFailureWhenReenablingFails() {
        Usuario tecnico = usuario(Papel.TECNICO);
        when(chamados.contarNaoFinalizados(tecnico.getSub())).thenReturn(0L, 1L);
        doThrow(new IllegalStateException("Keycloak fora")).when(keycloak).reabilitar(tecnico.getSub());

        assertRegra(tecnico, null, CodigoErro.SUBSTITUTO_OBRIGATORIO);
    }

    private void assertRegra(Usuario usuario, UUID substitutoId, CodigoErro codigo) {
        assertThatThrownBy(() -> servico.inativar(usuario.getId(), substitutoId, GESTOR))
                .isInstanceOfSatisfying(RegraNegocioException.class, e -> assertThat(e.getCodigo()).isEqualTo(codigo));
    }

    private Usuario usuario(Papel perfil) {
        Usuario usuario = new Usuario(UUID.randomUUID(), "Nome", "n@x.local", perfil, null);
        ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
        when(usuarios.findById(usuario.getId())).thenReturn(Optional.of(usuario));
        return usuario;
    }
}
