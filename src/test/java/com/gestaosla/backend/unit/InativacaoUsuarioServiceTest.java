package com.gestaosla.backend.unit;

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

import com.gestaosla.backend.auditoria.api.AuditoriaService;
import com.gestaosla.backend.identidade.api.ChamadosDoTecnico;
import com.gestaosla.backend.identidade.api.KeycloakAdminClient;
import com.gestaosla.backend.identidade.internal.InativacaoTestHelper;
import com.gestaosla.backend.identidade.internal.InativacaoTestHelper.Pessoa;
import com.gestaosla.backend.shared.api.Ator;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.Papel;
import com.gestaosla.backend.shared.api.RegraNegocioException;

/** Regras e ordem do fluxo, com os colaboradores simulados. O fluxo real está em InativacaoUsuarioTest. */
class InativacaoUsuarioServiceTest {

    private static final Ator GESTOR = Ator.usuario(UUID.randomUUID(), Papel.GESTOR);

    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final AuditoriaService auditoria = mock(AuditoriaService.class);
    private final ChamadosDoTecnico chamados = mock(ChamadosDoTecnico.class);
    private final InativacaoTestHelper cenario = new InativacaoTestHelper(keycloak, auditoria, Optional.of(chamados));

    @Test
    void shouldRejectLastActiveManagerBeforeCallingKeycloak() {
        Pessoa gestor = cenario.usuario(Papel.GESTOR);
        cenario.gestoresAtivos(1L);

        assertRegra(gestor, CodigoErro.ULTIMO_GESTOR_ATIVO);

        verifyNoInteractions(keycloak);
    }

    // Dois gestores inativando um ao outro: a checagem inicial passa para os dois; a da transação, depois da trava, barra.
    @Test
    void shouldRecheckLastManagerInsideTransactionAndReenableAccount() {
        Pessoa gestor = cenario.usuario(Papel.GESTOR);
        cenario.gestoresAtivos(2L, 1L);

        assertRegra(gestor, CodigoErro.ULTIMO_GESTOR_ATIVO);

        InOrder ordem = inOrder(keycloak);
        ordem.verify(keycloak).desabilitar(gestor.sub());
        ordem.verify(keycloak).reabilitar(gestor.sub());
        assertThat(cenario.travas()).isEqualTo(1);
        assertThat(cenario.ativo(gestor.id())).isTrue();
    }

    @Test
    void shouldRequireSubstituteBeforeCallingKeycloak() {
        Pessoa tecnico = cenario.usuario(Papel.TECNICO);
        when(chamados.contarNaoFinalizados(tecnico.sub())).thenReturn(1L);

        assertRegra(tecnico, CodigoErro.SUBSTITUTO_OBRIGATORIO);

        verifyNoInteractions(keycloak);
    }

    // Chamado atribuído entre a validação e a transação.
    @Test
    void shouldRecheckOpenTicketsInsideTransaction() {
        Pessoa tecnico = cenario.usuario(Papel.TECNICO);
        when(chamados.contarNaoFinalizados(tecnico.sub())).thenReturn(0L, 1L);

        assertRegra(tecnico, CodigoErro.SUBSTITUTO_OBRIGATORIO);

        verify(keycloak).reabilitar(tecnico.sub());
        verify(auditoria, never()).registrar(any(), any(), any(), any(), any(), any());
    }

    // Duas inativações do mesmo usuário: a segunda acha o registro já inativo depois da trava e não audita de novo.
    @Test
    void shouldSkipWhenAnotherInactivationCommittedWhileWaitingForLock() {
        Pessoa tecnico = cenario.usuario(Papel.TECNICO);
        cenario.inativadoPorOutraTransacaoAoTravar(tecnico.id());

        cenario.inativar(tecnico.id(), null, GESTOR);

        verify(auditoria, never()).registrar(any(), any(), any(), any(), any(), any());
        verify(keycloak, never()).reabilitar(any());
    }

    @Test
    void shouldInactivateTechnicianWhileChamadoModuleDoesNotExist() {
        InativacaoTestHelper semModulo = new InativacaoTestHelper(keycloak, auditoria, Optional.empty());
        Pessoa tecnico = semModulo.usuario(Papel.TECNICO);

        semModulo.inativar(tecnico.id(), null, GESTOR);

        assertThat(semModulo.ativo(tecnico.id())).isFalse();
    }

    // O TransactionManager simulado não faz rollback: depois da falha, o registro segue inativo, como quando outra
    // inativação do mesmo usuário já comitou. Reabilitar deixaria a conta aberta com o registro inativo.
    @Test
    void shouldNotReenableAccountWhenRecordEndsInactive() {
        Pessoa tecnico = cenario.usuario(Papel.TECNICO);
        doThrow(new IllegalStateException("falha na auditoria")).when(auditoria).registrar(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> cenario.inativar(tecnico.id(), null, GESTOR)).isInstanceOf(IllegalStateException.class);

        assertThat(cenario.ativo(tecnico.id())).isFalse();
        verify(keycloak, never()).reabilitar(any());
    }

    @Test
    void shouldKeepOriginalFailureWhenReenablingFails() {
        Pessoa tecnico = cenario.usuario(Papel.TECNICO);
        when(chamados.contarNaoFinalizados(tecnico.sub())).thenReturn(0L, 1L);
        doThrow(new IllegalStateException("Keycloak fora")).when(keycloak).reabilitar(tecnico.sub());

        assertRegra(tecnico, CodigoErro.SUBSTITUTO_OBRIGATORIO);
    }

    private void assertRegra(Pessoa usuario, CodigoErro codigo) {
        assertThatThrownBy(() -> cenario.inativar(usuario.id(), null, GESTOR))
                .isInstanceOfSatisfying(RegraNegocioException.class, e -> assertThat(e.getCodigo()).isEqualTo(codigo));
    }
}
