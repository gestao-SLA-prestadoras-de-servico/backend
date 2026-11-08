package com.gestaosla.backend.shared.api;

/**
 * Catálogo de códigos do campo {@code codigo} das respostas de erro. O frontend decide a mensagem por ele,
 * então um código publicado não muda de nome. Ver docs/erros-api.md.
 */
public enum CodigoErro {

    // Gerais: definidos pelo GlobalExceptionHandler conforme o status
    VALIDACAO,
    NAO_AUTENTICADO,
    ACESSO_NEGADO,
    RECURSO_NAO_ENCONTRADO,
    CONFLITO_VERSAO,
    REQUISICAO_INVALIDA,
    ERRO_INTERNO,
    SERVICO_INDISPONIVEL,       // RN-137: serviço externo fora do ar ou timeout esgotado (503)

    // Conflitos: lançados via ConflitoException (409)
    USUARIO_JA_EXISTE,          // RN-127: conta com mesmo username ou e-mail no Keycloak

    // Regras de negócio: lançados via RegraNegocioException (422)
    TRANSICAO_INVALIDA,         // RN-11, RN-13
    CONTRATO_FORA_VIGENCIA,     // RN-01
    CONTRATO_SEM_POLITICA,      // RN-03
    ORDEM_PRAZOS_INVALIDA,      // RN-30, RN-32
    EVIDENCIA_OBRIGATORIA,      // RN-72
    CANCELAMENTO_NAO_PERMITIDO, // RN-19
    REATRIBUICAO_NAO_PERMITIDA, // RN-20
    REABERTURA_NAO_PERMITIDA,   // RN-93, RN-99
    CLIENTE_OBRIGATORIO,        // RF-09: usuário de perfil CLIENTE precisa da empresa
    CLIENTE_INEXISTENTE,        // RF-09: empresa informada não existe
    CLIENTE_INATIVO,            // cadastro-01: cliente inativo não aceita novo usuário
    CLIENTE_NAO_PERMITIDO,      // RF-09: técnico e gestor não pertencem a empresa cliente
    SUBSTITUTO_OBRIGATORIO,     // RN-133: técnico com chamados não finalizados exige substituto
    SUBSTITUTO_INVALIDO,        // RN-133: substituto inexistente, inativo, não técnico ou o próprio inativado
    AUTOINATIVACAO_NAO_PERMITIDA, // autenticação-06: gestor não inativa a si mesmo
    ULTIMO_GESTOR_ATIVO         // autenticação-06: o sistema não pode ficar sem gestor ativo
}
