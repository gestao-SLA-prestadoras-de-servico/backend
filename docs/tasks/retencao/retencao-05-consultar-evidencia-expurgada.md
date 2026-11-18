# [retencao - 05] Consultar evidência expurgada

> RF-46, RN-104 · Dependência: retenção-02 · Issue #78

## Contexto

O gestor precisa consultar a prova de que uma evidência existiu depois da retirada da imagem do MinIO.

| Critério | Solução |
| --- | --- |
| Consultar metadados | `GET /api/evidencias/{id}` retorna id, atendimento, chamado, envio, expurgo, hash e estado. |
| Listagem com estado e sem miniatura | `GET /api/chamados/{id}/evidencias` inclui o item `EXPURGADA` na visão do gestor. O download de qualquer variante devolve 410. |
| Restringir cliente e técnico | A consulta individual devolve 403, e as listagens desses perfis omitem os itens expurgados. |

## Passos técnicos

- Registrar a regra de rota para gestor antes da regra geral de download e aplicar `@PreAuthorize`.
- Reusar a busca da evidência com checagem do escopo do chamado.
- Atualizar o contrato OpenAPI e os testes de consulta.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Origem dos metadados | Consultar a linha de evidência no PostgreSQL | A prova de rastreabilidade permanece disponível com o prefixo vazio no MinIO. |
| Listagens de cliente e técnico | Omitir o item expurgado | O formato dos itens que esses perfis podem consultar permanece consistente. |

## Atenção ao atualizar um ambiente existente

Listagens de cliente e técnico passam a conter apenas evidências com estado diferente de `EXPURGADA`.

## Como verificar

Consulte o [registro dos testes](../../testes/retencao/retencao-05-consultar-evidencia-expurgada.md).
