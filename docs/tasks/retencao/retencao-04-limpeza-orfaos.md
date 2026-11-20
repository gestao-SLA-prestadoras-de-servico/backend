# [retencao - 04] Remover objetos órfãos do storage

> RF-47, RN-109 e RN-112 · Dependências: retenção-01, evidência-01 e evidência-02 · Issue #77

## Contexto

A recepção grava o objeto no MinIO antes de persistir a evidência. Uma falha no banco pode deixar o objeto sem vínculo. O processamento também pode deixar artefatos que não chegaram a ser registrados.

| Critério | Solução |
| --- | --- |
| Órfãos com mais de 24 horas são removidos | Compara a data de modificação do MinIO com o corte e verifica chamado, evidência e chaves registradas. |
| Objetos com 23 horas permanecem | O corte é estrito. Objetos no limite de 24 horas também permanecem. |
| `RECEBIDA` e `PROCESSANDO` são protegidas | Todos os objetos sob o prefixo de uma evidência nesses estados são preservados. A trava de linha impede que o estado mude durante a classificação e a remoção. |

## Passos técnicos

- Percorrer o bucket `evidencias` em páginas de até 100 objetos com o cursor `startAfter`.
- Interpretar o prefixo com `ChaveObjeto` e classificar cada objeto no módulo de evidências.
- Remover as chaves órfãs da página e registrar a contagem na etapa `orfaos`.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Paginação | Usar a última chave da página como cursor | A próxima página é encontrada mesmo quando a limpeza remove toda a página anterior. |
| Classificação | Preservar as chaves registradas em `DISPONIVEL` ou `FALHA`, e descartar arquivos remanescentes de `EXPURGADA` após o prazo de segurança | Uma evidência expurgada pode ter referência histórica ao original, mas o conteúdo deve continuar indisponível. |
| Chave fora da convenção | Tratar como órfã no bucket exclusivo de evidências | Uma chave que não corresponde a um prefixo válido não é gerada pela aplicação. |

## Atenção ao atualizar um ambiente existente

Confirme que o bucket `evidencias` contém apenas objetos da aplicação antes de agendar o primeiro ciclo.

## Como verificar

Consulte o [registro dos testes](../../testes/retencao/retencao-04-limpeza-orfaos.md).
