# [relatorio - 02] Exportar relatório de cumprimento de SLA

Data: 2026-11-22 · Branch: `feat/relatorio`

## Resultados

| O que | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- |
| Compilação limpa e suíte completa | `TZ=UTC ./mvnw clean test` | Compila e executa toda a suíte | 539 testes, 0 falhas, 0 erros, 0 ignorados; BUILD SUCCESS | ✅ |
| Exportações, agregações e autorização por rota | `TZ=UTC ./mvnw -Dtest=com.gestaosla.backend.integration.RelatorioSlaTest,com.gestaosla.backend.integration.AcessoPorRotaTest,com.gestaosla.backend.integration.OpenApiContratoTest test` | CSV/PDF, filtros, autorização e contrato passam | 18 testes, 0 falhas, 0 erros; BUILD SUCCESS antes da inclusão de nomes nas exportações | ✅ |
| Compilação isolada | `TZ=UTC ./mvnw -o -DskipTests test-compile` | Código de produção e testes compilam | Passou | ✅ |
| Verificação manual no ambiente dev | Downloads no navegador/Excel e impressão do PDF | Conferir apresentação real e equivalência visual com a tela | Não executada nesta task | ⏳ |

## Observações

- Os testes verificam BOM UTF-8, acentos, separador `;`, decimais com vírgula para Excel pt-BR, valores das três seções, parâmetros de filtro, nomes e cabeçalhos de download, extração do conteúdo textual do PDF e autorização das duas rotas.
- O `OpenApiContratoTest` regenerou `docs/openapi.json` para incluir as duas rotas e seus parâmetros/tipos de resposta; a execução seguinte confirmou o contrato atualizado.
- Na primeira execução integrada, o teste de agregação do relatório encontrou dados de outra fixture com o mesmo instante fixo. O teste agora usa um instante exclusivo para a massa e passou na repetição e na suíte completa.
- `Surefire is going to kill self fork JVM` foi emitido após os 539 testes passarem e o processo sair com `BUILD SUCCESS`; não alterou o resultado.
- Testes com token real e a apresentação visual no ambiente dev não foram verificados manualmente.
- A revisão seguinte da apresentação passou a incluir razão social/nome e UUID; a validação automatizada desta alteração fica registrada em nova execução abaixo.
