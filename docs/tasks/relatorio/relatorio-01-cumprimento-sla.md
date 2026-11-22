# [relatorio - 01] Consultar cumprimento de SLA

> Requisito: RF-38 · Depende de: [sla - 05]

## Contexto

O gestor precisa acompanhar o cumprimento dos SLAs por período, cliente e técnico. O relatório usa exclusivamente as apurações congeladas no encerramento, sem recalcular resultados; chamados cancelados não têm apuração e ficam fora.

## Critérios de aceite × solução

| Critério | Solução |
| --- | --- |
| `GET /api/relatorios/sla` restrito a GESTOR, com filtros de período, cliente e técnico | Controller valida datas e delega ao serviço; regras de rota e `@PreAuthorize` exigem GESTOR. |
| Cumpridos, violados e percentual por relógio, total e quebra por cliente/técnico | Consultas SQL agregadas na tabela imutável `apuracao_sla`; percentual = cumpridos / (cumpridos + violados) × 100. |
| A quebra por técnico não atribui o relógio de resposta | Consulta agrupada por técnico exclui `RESPOSTA`. |
| Cancelados não entram | O relatório só consulta `apuracao_sla`, que não é criada para cancelados (RN-19). |
| Massa de resultados conhecidos | Teste de integração insere registros sintéticos e valida contagens, percentual e filtros. |

## Passos técnicos

- [x] Criar consultas agregadas SQL no módulo `relatorio`.
- [x] Criar `RelatorioSlaService` e DTOs de resposta.
- [x] Criar endpoint para gestores.
- [x] Criar massa de teste com valores conhecidos.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Data do filtro | `congelada_em`, início e fim inclusivos | Corresponde à fotografia persistida e evita recalcular histórico. |
| Filtro de técnico | Aplica-se às agregações geral e por cliente também | Mantém os filtros coerentes em todo o relatório; resposta continua fora da quebra por técnico. |
| Percentual | Duas casas decimais; denominador zero retorna 0 | Fórmula: cumpridos / (cumpridos + violados) × 100. |
| Nenhum resultado | HTTP 200, coleções vazias e mensagem neutra | Comunica ausência de dados sem tratar como falha. |
| Identificação | Resposta expõe IDs, sem resolver nomes | O frontend já consegue correlacionar os identificadores. |
| Identificador do técnico | `tecnicoSub`, não `tecnicoId` | O valor é o `usuario.sub` copiado para `apuracao_sla.tecnico_sub`, como no filtro de chamados. |
| Acesso do teste ao service | Helper público em `relatorio.internal` (`RelatorioSlaTestHelper`) | O service fica package-private e o CI só roda testes em `integration/` e `unit/`. |
| Datas no SQL | `OffsetDateTime` em UTC | O driver PostgreSQL não infere tipo para `Instant`. |
| `GROUP BY`/`ORDER BY` dinâmicos | Só as colunas agrupadas, nunca o literal `null` | PostgreSQL rejeita constante não inteira em `GROUP BY` e `ORDER BY`. |

## Como verificar

Ver `docs/testes/relatorio/relatorio-01-cumprimento-sla.md`.
