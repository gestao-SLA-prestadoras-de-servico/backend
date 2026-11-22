# [relatorio - 02] Exportar relatório de cumprimento de SLA

> Requisito: RF-39 · Depende de: [relatorio - 01]

## Contexto

O relatório gerencial precisa ser exportado para envio ao cliente ou uso em reuniões. PDF e CSV devem apresentar os mesmos números da tela para os mesmos filtros.

## Critérios de aceite × solução

| Critério | Solução |
| --- | --- |
| CSV e PDF aceitam os filtros do relatório | Novas rotas `GET /api/relatorios/sla.csv` e `GET /api/relatorios/sla.pdf` recebem início, fim, cliente e técnico. |
| CSV abre com acentuação correta no Excel em pt-BR | UTF-8 com BOM, separador `;`, decimais com vírgula e linhas terminadas em CRLF. |
| PDF mostra filtros, geração e tabelas por relógio, cliente e técnico | Documento A4 retrato, com cabeçalho contextual e tabelas com cabeçalhos repetidos. |
| Nome do arquivo contém o período | `relatorio-sla_yyyy-MM-dd_a_yyyy-MM-dd` com a extensão correspondente, datas no fuso configurado. |
| Exportações batem com a tela | Ambas serializam o mesmo DTO agregado usado por `GET /api/relatorios/sla`. |
| Arquivos são enviados como download | Respostas usam `Content-Disposition: attachment` e o tipo de mídia do formato. |

## Passos técnicos

- [x] Adicionar OpenPDF 3.0.5 e fontes Liberation para caracteres acentuados no PDF.
- [x] Gerar CSV tabular com seções geral, cliente e técnico.
- [x] Gerar PDF A4 retrato com filtros, data de geração e três seções de resultados.
- [x] Adicionar endpoints protegidos para gestores e nomes de arquivo com período.
- [x] Adicionar testes de conteúdo, cabeçalhos, filtros e autorização.
- [x] Atualizar contrato OpenAPI e roteiro de testes manuais.

## Decisões

| Tema | Decisão | Motivo |
| --- | --- | --- |
| Rotas | `/api/relatorios/sla.csv` e `/api/relatorios/sla.pdf` | Mantém o prefixo e o recurso do endpoint da tela. |
| Organização do CSV | Tabela única com coluna `secao` e colunas de IDs opcionais | Facilita filtro, ordenação e importação no Excel. |
| Identificação nas exportações | Razão social/nome junto do UUID | Arquivos enviados a clientes e reuniões precisam ser legíveis e rastreáveis. Os nomes refletem os cadastros atuais; as apurações não guardam snapshot dos nomes. |
| Resolução de nomes | Portas públicas em lote de cadastro e identidade | Mantém a fronteira dos módulos e evita consulta direta às tabelas de outros módulos. |
| Técnico sem cadastro local | Exibir “Nome não localizado” e manter o UUID | Preserva identificação do histórico sem ocultar o dado persistido na apuração. |
| Layout do PDF | A4 retrato com tabelas em páginas contínuas | Mantém o documento imprimível e legível; os cabeçalhos reaparecem após quebra de página. |
| Fonte PDF | Liberation Sans incorporada ao documento | Garante acentuação e texto em português. |
| OpenPDF | `com.github.librepdf:openpdf` e `openpdf-fonts-extra`, versão 3.0.5 | Compatível com o Java 21 do projeto. |
| Data de geração e nome | Fuso configurado; datas do nome no formato `yyyy-MM-dd` | Coerência com o fuso da aplicação e nome de arquivo portátil. |

## Como verificar

Ver `docs/testes/relatorio/relatorio-02-exportar-cumprimento-sla.md`.
