# Testes — [retencao - 01] Criar a rotina diária de retenção e limpar a outbox

> 18/11/2026 · Branch `feat/retencao` · PostgreSQL descartável via Testcontainers

| O que foi testado | Como | Esperado | Obtido | Status |
| --- | --- | --- | --- | --- |
| Expiração, pendentes, lotes e métricas | `LimpezaOutboxTest` com Java 21 e Testcontainers | Manter 101 no limite, remover após o limite e preservar o pendente | 1 teste passou | Passou |
| Fronteira de sete dias | Trocar `<` por `<=` em uma cópia temporária e rodar `LimpezaOutboxTest` | O teste deve detectar a exclusão antecipada | Falhou na fronteira com `expected: 101 but was: 0` | Quebra detectada |

## Observações

- O cenário de integração usa relógio mutável e dados sintéticos. O roteiro de dev não foi executado.
- A execução da suíte completa com `TZ=UTC` registrou timeout de inicialização do Keycloak em `CadastroUsuarioTest` e falha de comparação textual de schemas com CRLF em `ContratoNotificacaoTest`.
