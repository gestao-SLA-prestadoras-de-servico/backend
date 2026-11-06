package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.gestaosla.backend.auditoria.api.OperacaoAuditada;

class OperacaoAuditadaTest {

    // Lista da RN-121 (docs/flowSLA.md), item a item.
    @Test
    void shouldCoverEveryOperationOfRn121() {
        assertThat(Arrays.stream(OperacaoAuditada.values()).map(Enum::name)).contains(
                "MUDANCA_ESTADO",                    // mudança de estado
                "ALTERACAO_PRIORIDADE",              // alteração de prioridade
                "ATRIBUICAO",                        // atribuição
                "REATRIBUICAO",                      // reatribuição
                "TRANSFERENCIA_POR_INATIVACAO",      // transferência de chamados por inativação de técnico
                "REABERTURA",                        // reabertura
                "ALTERACAO_POLITICA",                // alteração de política
                "ENVIO_EVIDENCIA",                   // envio de evidência
                "REPROCESSAMENTO",                   // reprocessamento
                "CANCELAMENTO",                      // cancelamento
                "VALIDACAO_AUTOMATICA",              // validação automática
                "EXPURGO_EVIDENCIA",                 // expurgo
                "ALTERACAO_PREFERENCIA_NOTIFICACAO", // alteração da preferência de notificação do gestor
                "CADASTRO_USUARIO",                  // cadastro de usuários
                "INATIVACAO_USUARIO");               // inativação de usuários
    }
}
