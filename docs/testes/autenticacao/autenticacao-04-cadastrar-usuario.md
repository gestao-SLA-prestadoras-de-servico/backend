# Testes — [autenticação - 04] Cadastrar usuário

> Executados em 03/11/2026, na branch `feat/autenticacao-04-plataforma-06` (alterações ainda não commitadas). Suíte Maven rodada numa cópia do projeto (o `target/maven-status` do repositório pertence ao root, ver plataforma-04), com Testcontainers: `pgvector/pgvector:pg17`, `quay.io/keycloak/keycloak:26.4` com o `realm-flowsla.json` e `axllent/mailpit:v1.27`. Verificação manual no dev, com token real do gestor.

## Resultados

| # | O que foi testado | Como | Resultado esperado | Resultado obtido | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Suíte completa | `TZ=UTC ./mvnw clean test` | tudo passa | 101 testes, 0 falhas (`CadastroUsuarioTest` 9, `AcessoPorRotaTest` 4, `NovoUsuarioValidacaoTest` 3 e os anteriores) | ✅ |
| 2 | Validação do request (unitário) | `NovoUsuarioValidacaoTest` | técnico sem empresa válido; nome, e-mail e perfil obrigatórios; nenhum campo de senha no record | passou | ✅ |
| 3 | Cadastro completo | `shouldCreateTechnicianWithRoleLocalRecordAuditAndPasswordEmail` (e-mail em maiúsculas) | 201; e-mail em minúsculas; conta no Keycloak com o mesmo `sub` e papel `TECNICO`; registro local ativo; auditoria com o `sub` do gestor, sem e-mail; 1 e-mail no Mailpit | passou | ✅ |
| 4 | Usuário cliente | `shouldLinkClientUserToActiveCompanyAndExposeItAsCurrentUser` | 201 com `clienteId`; `/me` do novo usuário devolve a empresa | passou | ✅ |
| 5 | Empresa inconsistente | `shouldRejectInconsistentCompanyWith422BeforeCallingKeycloak` | 422 `CLIENTE_OBRIGATORIO`, `CLIENTE_INEXISTENTE`, `CLIENTE_INATIVO`, `CLIENTE_NAO_PERMITIDO`; nenhuma conta criada no Keycloak | passou | ✅ |
| 6 | E-mail repetido | `shouldReturn409ForEmailAlreadyRegisteredIgnoringCase` | 409 `USUARIO_JA_EXISTE` | passou | ✅ |
| 7 | Compensação | `shouldRemoveKeycloakAccountWhenLocalWriteFails` (falha simulada de banco na transação local) | 500; conta removida do Keycloak; nenhum registro local | passou | ✅ |
| 8 | E-mail falha após o commit | `shouldKeepRegistrationWhenPasswordEmailFailsAfterCommit` | 201 com `emailDefinicaoSenhaEnviado: false`; conta e registro mantidos | passou | ✅ |
| 9 | Conta órfã | `shouldReuseOrphanKeycloakAccount` (conta criada direto no Keycloak) | 201 com o mesmo `sub` | passou | ✅ |
| 10 | Só gestor | `shouldAllowOnlyManager` | 403 para técnico | passou | ✅ |
| 11 | Sem coluna de senha | `shouldHaveNoPasswordColumn` | nenhuma coluna `senha`/`password` em `usuario` | passou | ✅ |
| 12 | Contrato | `OpenApiContratoTest` | `POST /api/usuarios` com 201, 400, 401, 403, 409, 422, 500, 503 | passou depois de regenerar o `docs/openapi.json` | ✅ |
| 13 | Dev: fluxo com token real | Login PKCE do gestor; `POST /api/usuarios` técnico (e-mail com maiúsculas) e cliente; e-mail repetido; cliente inativado e novo cliente nele | 201 / 201 / 409 `USUARIO_JA_EXISTE` / 422 `CLIENTE_INATIVO`; 2 e-mails no Mailpit; 2 registros `CADASTRO_USUARIO` com o `sub` do gestor e só perfil/`clienteId` | todos conforme o esperado | ✅ |
| 14 | Teste manual completo (QA) | Subagente QA executou `docs/plano-testes-manuais.md` do zero (`make run-dev-down`), por HTTP | todos os passos conforme o plano | 54 ✅, 0 ❌, 5 ⚠️ (4 por execução sem navegador; 1 = defeito D1, obs. 5) | ✅ |
| 15 | D1 corrigido | `AcessoPorRotaTest`: técnico e cliente em todas as rotas de gestor com corpo `{}` e `{` | 403 `ACESSO_NEGADO`; gestor com `{}` continua 400; `/me` aberto a todos | passou; sem as regras de rota o teste falha com 400 (prova feita) | ✅ |
| 16 | D1 no dev | Ana (técnica) com token real: `POST /api/usuarios` e `/api/clientes` com `{}` e `{` | 403 | 403 `ACESSO_NEGADO` nos quatro; gestor com `{}` → 400 `VALIDACAO` | ✅ |

## Observações

1. **Achado (teste):** o stub de `AuditoriaService` feito pelo proxy transacional (`MANDATORY`) disparava a própria checagem de transação, e o stub incompleto vazava para o teste seguinte, que dava 500. O stub agora é feito no objeto por trás do proxy (`AopTestUtils.getUltimateTargetObject`).
2. **Não verificado:** falha **na compensação** (Keycloak fora do ar no momento de remover). O caminho só registra o erro; a conta órfã é reaproveitada no próximo cadastro (coberto no item 9).
3. **Não verificado:** dois cadastros simultâneos do mesmo e-mail. A guarda está no código (`existsBySub` antes de remover), mas não há teste de concorrência.
4. **Pendente:** endpoint para reenviar o e-mail de definição de senha quando `emailDefinicaoSenhaEnviado` vier `false`.
5. **Achado do QA (D1, baixa):** técnico ou cliente com corpo inválido em rota de gestor recebia **400** com a lista de campos, em vez de 403: o `@PreAuthorize` só roda depois da validação do `@RequestBody`. Corrigido com regras de rota por módulo na cadeia de segurança (itens 15 e 16).
6. **Observações do QA ainda abertas:** mensagens do Bean Validation em inglês ("must not be blank") ao lado das de UF/CEP em português; JSON malformado ou id não-UUID trazem no `detail` o texto do framework ("Failed to read request"); e-mail com espaços nas pontas é rejeitado (400) em vez de aparado.
