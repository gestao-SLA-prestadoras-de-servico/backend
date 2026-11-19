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
    CODIGO_CONTRATO_JA_EXISTE,  // cadastro-02: código de contrato é único no sistema
    NOME_POLITICA_JA_EXISTE,    // cadastro-03: nome de política de SLA é único

    // Download de evidência (410)
    EVIDENCIA_EXPURGADA,        // RN-81, RN-104: o objeto foi expurgado; só os metadados restam

    // Recepção de evidência (413)
    ARQUIVO_MUITO_GRANDE,       // RN-75: arquivo acima de 10 MB; lançado pelo limite de multipart

    // Regras de negócio: lançados via RegraNegocioException (422)
    TRANSICAO_INVALIDA,         // RN-11, RN-13
    POSICIONAMENTO_JA_REGISTRADO, // chamado-06: o primeiro posicionamento só é registrado uma vez (RN-23)
    CONTRATO_FORA_VIGENCIA,     // RN-01
    CONTRATO_SEM_POLITICA,      // RN-03
    POLITICA_INEXISTENTE,       // cadastro-04: política informada no vínculo não existe
    ORDEM_PRAZOS_INVALIDA,      // RN-30, RN-32
    EVIDENCIA_OBRIGATORIA,      // RN-72
    DESCRICAO_TECNICA_OBRIGATORIA, // RN-72: concluir o atendimento exige a descrição técnica (chamado-11)
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
    ULTIMO_GESTOR_ATIVO,        // autenticação-06: o sistema não pode ficar sem gestor ativo
    FORMATO_NAO_SUPORTADO,      // RN-75: os bytes não são JPEG, PNG nem WebP (a extensão e o Content-Type não contam)
    IMAGEM_MUITO_GRANDE,        // RN-75: dimensões do cabeçalho acima do teto de megapixels
    IMAGEM_INVALIDA,            // RN-75: assinatura válida, mas a imagem está corrompida
    REPROCESSAMENTO_NAO_PERMITIDO // RN-121: só a evidência em FALHA é reprocessada (evidencia-05)
}
