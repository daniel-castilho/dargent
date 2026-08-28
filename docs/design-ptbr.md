# Payment Processing & Transaction System — Documento de Design

| | |
|---|---|
| **Versão** | 1.0.1 |
| **Data** | 2026-08-28 |
| **Status** | Aprovado — consolidação do brainstorm (16 rodadas de debate) |
| **Nome** | **Dargent** — do francês *d'argent*, "de prata / de dinheiro" (lat. *argentum*). Colisão direta zero; ressalva de adjacência de busca com o namespace "Argent" documentada em D22. História: codinome "Cobre" aposentado por colidir com fintech LatAm homônima do mesmo domínio |
| **Objetivo** | Projeto de portfólio: backend de infraestrutura de pagamentos (estilo PSP — Stripe/Razorpay/Efí) demonstrando engenharia de sistemas distribuídos, consistência financeira e operação on-premises |

> **Documento vivo.** Este é a fonte da verdade do projeto. Mudanças de design passam por revisão neste documento e incrementam a versão (histórico no Apêndice C). Fontes de código em inglês; este documento em PT-BR pode ser traduzido para o repo.

---

## 1. Sumário Executivo

Um backend que implementa o **ciclo de vida completo de um pagamento**: criar → processar → verificar → webhook → sucesso/falha → reembolso — usando **PIX com QR dinâmico** como método de pagamento, mediado por um **PSP simulado**.

O valor do projeto não está no CRUD de pagamentos, e sim nas garantias:

- **Idempotência** de ponta a ponta (request do merchant, webhook do PSP, mensagem na fila)
- **Máquina de estados** imposta pelo banco (UPDATE condicional), imune a corridas
- **Outbox transacional** → SNS/SQS FIFO (LocalStack) → consumidores idempotentes
- **Ledger de dupla entrada** append-only com prova de balanço
- **Reconciliação** contra o PSP quando o webhook não chega
- **Operação real on-premises**: Docker Compose + NGINX blue-green com canary, CI com gates de segurança

### 1.1 Metas

| Meta | Como se prova |
|---|---|
| Nenhum pagamento cobrado duas vezes, sob nenhuma corrida | Testes de concorrência com `CyclicBarrier` + idempotency keys |
| Nenhum pagamento confirmado é perdido — nem sem webhook | Cenário de caos: webhook suprimido → reconciler confirma (rodando no CI) |
| Todo centavo rastreável e balanceado | Ledger dupla-entrada + job de prova de balanço + property tests (jqwik) |
| Deploy sem downtime on-premises | Blue-green com canary no NGINX, rollback instantâneo, smoke de shutdown sob carga no CI |
| Qualidade auditável | Matriz de aceitação por milestone (requisito → teste → evidência) + gates de segurança no CI |

### 1.2 Não-metas (v1.0)

| Fora do escopo | Motivo |
|---|---|
| Cloud / k8s | Decisão: rodar bare metal on-premises com Docker Compose |
| Cartão de crédito no core | Stretch goal (provar abstração Strategy no fim) |
| QR Code estático (P2P) | Complica reconciliação sem agregar; QR dinâmico only |
| Saques (payouts) | Cortado por foco; refunds drenam o saldo do merchant |
| Redis | Stretch (cache de leitura / rate limit) — fora do core |
| Tracing distribuído | Monólito + correlation id nos logs bastam; stretch se extrair serviços |
| KYC/onboarding de merchants, compliance real (PCI/Bacen) | O sistema é um PSP simulado; postura PCI = nunca armazenar dados sensíveis |
| Dashboard web | API REST apenas; Swagger UI como interface de exploração |

---

## 2. Registro de Decisões Cravadas

Decisões fechadas em debate, listadas para rastreabilidade. Racionais completos nos ADRs (§15).

| # | Decisão | Valor cravado |
|---|---|---|
| D1 | Arquitetura | Monólito modular, hexagonal, extraível para microserviços |
| D2 | Split de módulos | Por **bounded context** (payments, ledger, notifications), nunca por camada técnica |
| D3 | Mensageria | SNS/SQS FIFO no LocalStack via **AWS SDK v2 direto** em channel adapters próprios |
| D4 | Portabilidade de broker | Envelope de evento próprio + ports; trocar Kafka/Rabbit = tocar só adapters |
| D5 | Método de pagamento | PIX, **QR dinâmico apenas**; cartão é stretch (Strategy) |
| D6 | Webhook após expiração | **Ressurreição**: confiar no PSP, aceitar pagamento tardio + trilha de auditoria |
| D7 | Taxa | Calculada no `payments` (bps); **evento carrega o breakdown**; ledger é contábil burro |
| D8 | Taxa no refund | **Devolvida proporcionalmente** ao merchant (estorno de receita) |
| D9 | Saldo | **Projection materializada** atualizada na mesma transação do consumer (CQRS-lite) |
| D10 | Saques | **Não** entram no v1 |
| D11 | Estado canônico | `PENDING` (não "WAITING_PAYMENT") |
| D12 | Autenticação | **API key Stripe-style** (`psp_test_…`, SHA-256 no banco, prefixo indexável) |
| D13 | Listagem de pagamentos | Cursor pagination, no escopo do core |
| D14 | Acesso a dados | **JPA/Hibernate no payments** (entidade de domínio separada) · **`JdbcClient` puro no ledger** (zero JPA) |
| D15 | Stack | Java **25** LTS · Spring Boot **4.1.x** · Maven multi-módulo · **PostgreSQL 16** · Flyway |
| D16 | Migrations | **Forward-only + expand/contract** (blue-green compartilha o Postgres; sem rollback scripts) |
| D17 | Refunds concorrentes | `SELECT FOR UPDATE` pessimista na linha do payment, escopo curtíssimo |
| D18 | Idempotência in-flight | `425 Too Early` + `Retry-After` (sem bloquear a request) |
| D19 | PSP timeout na criação | Retryable com backoff; pagamento nasce `PENDING` persistido; `FAILED` só após esgotar |
| D20 | Governança | Matriz de aceitação por milestone + pipeline completo com gates de segurança |
| D21 | Leitura de detalhe | `GET /payments/{txid}` direto na tabela (sem cache) |
| D22 | **Nome do projeto** | **Dargent** (*d'argent*, fr. "de prata/dinheiro"; lat. *argentum*). Critérios: história contável, encaixe técnico (`io.dargent`, `dargent_*`), colisão direta zero, pronunciável. Ressalva aceita: adjacência de busca com o namespace "Argent" (buscadores corrigem a query — descobribilidade via GitHub exato). Finalistas derrotados: Cuprum (🥈), Trilho, Vintém, Peagem, Lastro, Ábaco, Prata; "Cobre" vetado por colidir com fintech LatAm homônima do mesmo domínio |

---

## 3. Arquitetura

### 3.1 Visão geral do runtime

```
                        ┌────────────────────────── on-premises host ──────────────────────────┐
                        │                                                                       │
 merchant ──HTTP──▶ NGINX :8080 ──▶ api-blue :8081  ┐  mesma JVM, módulos:                   │
 payer app ──▶  (upstream weights,  api-green :8082 ┘   [ payments | ledger | notifications ] │
                canary, resolver DNS)                        │        ▲                       │
                                                             │ outbox │ eventos              │
                                                             ▼        │ (SNS→SQS FIFO)        │
                                                   ┌──────────────────────────┐               │
                                                   │ LocalStack :4566         │               │
                                                   │  SNS payment-events.fifo │               │
                                                   │  SQS ledger/notification │               │
                                                   └──────────────────────────┘               │
                                                             ▲                                │
                                     HTTP (cob / webhook)    │                                │
                                                             │                                │
                                                   psp-simulator :8090 ───────────────────────┘
                                                   (PSP do lojista + banco do pagador + caos)
                                                             │
                                                   PostgreSQL :5432  ◀── fonte da verdade
                                                   (schemas: payments | ledger | notifications)
```

### 3.2 Módulos do monólito (Maven multi-módulo)

| Módulo | Responsabilidade | Estilo |
|---|---|---|
| `modules/payments` | Lifecycle do pagamento, idempotência, outbox, webhook intake, reconciliação, expiração, refunds, BR Code | Hexagonal completo, domínio rico (JPA na borda) |
| `modules/ledger` | Dupla entrada append-only, projection de saldo, prova de balanço, settlement D+1 | Consumer de eventos puro, `JdbcClient` |
| `modules/notifications` | Consome eventos, enfileira "notificações" (log/registro), fase 2 | Pragmático, JPA simples |
| `modules/shared` | MÍNIMO transversal: `Money`, envelope de evento, contrato de erro, serialização JSON | Sem regra de negócio (gaveta de lixo proibida) |
| `apps/api` | Agrega os módulos, Boot embutido, schedulers, adapters de mensageria, Security, controllers | — |
| `apps/psp-simulator` | **Aplicação separada**: PSP do lojista + banco do pagador + knobs de caos | O "mundo externo" honesto |

### 3.3 Leis cross-module (verificadas por ArchUnit + script de import/FQN no CI)

1. Módulo **nunca** importa o adapter de outro módulo — só ports (`domain.port` / `application.port`).
2. `payments` **nunca chama** `ledger` sincronamente. `ledger` é downstream puro (consumer).
3. **Zero FK e zero JOIN entre schemas** no Postgres. Cada módulo é dono do seu schema.
4. Comunicação entre módulos **somente por eventos** (outbox → SNS → SQS).
5. Payload de webhook do PSP **nunca** vira entidade de domínio direto — tradução na borda (anti-corruption layer).
6. `shared` não depende de nenhum módulo.
7. `SecurityConfig` é fonte única de autorização; **endpoint novo sem regra explícita = violação** (regra do AGENTS.md).

### 3.4 Por que isso permite extração futura

A topologia já é de microserviços — apenas roda na mesma JVM: donos de dados separados, comunicação só por eventos em fila por consumidor, adapters de broker isolados. Extrair o `ledger` = mover o consumer pra outra JVM apontando pros mesmos tópicos. Rota: extrair `ledger` primeiro (trivial), `payments` depois.

### 3.5 Padrão arquitetural por módulo

```
<module>/src/main/java/io/dargent/<module>/
├── domain/            → entidades ricas, VOs auto-validados, exceções tipadas (zero framework)
│   ├── model/
│   └── port/          → in (use cases) | out (repos, publisher, PSP client, Clock)
├── application/       → serviços de use case (dependem só de ports)
└── adapter/
    ├── in/            → REST (thin), consumer de eventos
    └── out/           → persistence (JPA/JdbcClient), PSP client, SNS publisher
```

Domínio **puro onde o dinheiro vive** (`payments`, `ledger`); pragmatismo na periferia (`notifications`). Entidades com comportamento, **zero setters**, lifecycle forward-only guardado dentro da entidade (transição inválida lança → 409), factory methods, igualdade por identidade, VOs auto-validados (`Money`, `Txid`, `EndToEndId`), exceções tipadas de domínio.

---

## 4. Domínio de Pagamentos (PIX)

### 4.1 Máquina de estados

```
                 confirmar (webhook / reconciler / ressurreição)
   ┌─────────┐ ─────────────────────────────────▶ ┌───────────┐
   │ PENDING │                                    │ CONFIRMED │──▶ refund parcial ──┐
   └────┬────┘                                    └───────────┘◀── (volta p/ CONFIRMED? │
        │                                              ▲        não: PARTIALLY_REFUNDED│
        │ expirar (scheduler)                          │ outro refund parcial          ▼
        ▼                                              │                      ┌─────────────────────┐
   ┌─────────┐   ressurreição (confirm tardio) ────────┘                      │ PARTIALLY_REFUNDED  │
   │ EXPIRED │ ────────────────────────────────────────────────────────────▶ └──────────┬──────────┘
   └─────────┘        (vira CONFIRMED late=true + auditoria)        refund que zera ▼
        │                                                                ┌──────────┐
        │ PSP timeout esgotado na criação (D19)                          │ REFUNDED │ (terminal)
        ▼                                                                └──────────┘
   ┌─────────┐
   │ FAILED  │ (terminal)
   └─────────┘
```

**Regras:**
- **Toda transição é UPDATE condicional**: `UPDATE payments SET status=:novo, version=version+1 WHERE id=:id AND status IN (:permitidos) AND version=:v`. `rows affected = 0` → perdeu a corrida → reler e decidir. O banco é o árbitro.
- `EXPIRED` **não é terminal** (D6): confirmação tardia ressuscita com flag `late=true` + entrada de auditoria.
- O guardião das transições válidas vive **dentro da entidade** `Payment` (violação lança `InvalidTransitionException` → 409); o UPDATE condicional é a última linha de defesa.

| De → Para | Trigger | Guard |
|---|---|---|
| `PENDING → CONFIRMED` | Webhook `payment.confirmed` válido (HMAC ok, dedupe ok) **ou** reconciler confirma **ou** ressurreição | — (aceita vindo de `PENDING` **ou** `EXPIRED`) |
| `PENDING → EXPIRED` | Scheduler de expiração | `expires_at < now()` |
| `PENDING → FAILED` | PSP indisponível após esgotar retries da criação (D19) | tentativas ≥ máx |
| `CONFIRMED → PARTIALLY_REFUNDED` | Refund parcial criado | após o refund: `Σ refunds < amount` |
| `CONFIRMED → REFUNDED` | Refund que consome o remanescente | `Σ refunds = amount` |
| `PARTIALLY_REFUNDED → PARTIALLY_REFUNDED` | Outro refund parcial | idem |
| `PARTIALLY_REFUNDED → REFUNDED` | Refund que zera o remanescente | idem |

### 4.2 Especificidades PIX

| Conceito | Decisão |
|---|---|
| **txid** | Identificador público da cobrança, **25 chars alfanuméricos** (limite Bacen; ULID tem 26 — não cabe). Random alnum gerado na app + unique constraint + retry em colisão |
| **endToEndId** | ID fim-a-fim da rede PIX, gerado pelo PSP; preenche `payments.end_to_end_id` na confirmação; base do dedupe de webhook (`endToEndId + tipo`) |
| **QR dinâmico** | Apenas `cob` com txid e valor fixo. A app **gera o BR Code** (payload EMV com CRC16-CCITT) a partir dos dados da cob — implementação própria, testada |
| **Validade** | `expires_at` **copiado da resposta do PSP** — quem manda na validade é ele (default do simulador: 30 min) |
| **Dinheiro** | `Money` = `cents: Long` + moeda. BRL-only. **Nunca float**, nem no banco, nem no JSON, nem em memória. Taxa em **basis points** (default: 100 bps = 1%, configurável) |

### 4.3 Políticas de negócio

| Política | Decisão |
|---|---|
| **Criação retryable (D19)** | `POST /payments` persiste `PENDING` + idempotência, chama PSP com retry/backoff; esgotou → `FAILED`. PSP fora nunca "perde" a request do merchant |
| **Ressurreição (D6)** | Webhook de confirmação de cob expirada: aceitar (confiar no PSP — rejeitar dinheiro válido é pior), flag `late`, auditoria. O webhook que chega além da **janela anti-replay de 5 min** é rejeitado — e o reconciler salva o dia |
| **Reconciliação** | Job varre `PENDING` sem confirmação além de um limiar → `GET /cob/{txid}` no PSP → age conforme a verdade dele. Cobre webhook perdido/atrasado/rejeitado |
| **Expiração** | Scheduler com índice parcial (`WHERE status='PENDING' AND expires_at < now()`), UPDATE condicional. Sem mensagens atrasadas de SQS (delay máx 15 min não cobre cobranças de horas) |
| **Refunds (D17)** | N parciais por pagamento. Regra `Σ refunds ≤ amount`. Uma transação: `SELECT FOR UPDATE` na linha do payment → valida remanescente → insere refund → bump `version` → outbox |
| **Taxa no refund (D8)** | Devolvida proporcionalmente: refund de 40% devolve 40% da taxa ao merchant (estorno de receita no ledger) |

### 4.4 Fluxo principal (sequência)

```
Merchant            api/payments              psp-simulator             LocalStack           ledger
   │  POST /payments      │                        │                       │                  │
   │  Idempotency-Key     │  INSERT idem+payment   │                       │                  │
   │─────────────────────▶│  POST /cob ───────────▶│ txid, BR Code, expiry │                  │
   │                      │◀─── 201 (PENDING) ─────│                       │                  │
   │◀── 201 + txid + QR ──│  outbox: payment.created                        │                  │
   │                      │      relay ───────────────────────────────────▶│ SNS→SQS ────────▶│ (registra crédito pendente)
   │        pagador paga o QR no banco (endpoint do simulador)            │                  │
   │                      │◀── webhook assinado (HMAC + timestamp) ────────│                  │
   │                      │ valida HMAC, dedupe, UPDATE condicional        │                  │
   │                      │ → CONFIRMED, taxa em bps, endToEndId           │                  │
   │  GET /payments/{txid}│  outbox: payment.confirmed (amount, fee, net)  │                  │
   │─────────────────────▶│      relay ───────────────────────────────────▶│ SNS→SQS ────────▶│ DR clearing / CR pending+fees
   │◀── status + valores ─│                                                │                  │
```

---

## 5. Modelo de Dados

Schemas Postgres separados por módulo; Flyway com locations por módulo (cada jar carrega suas migrations); **forward-only** (D16).

### 5.1 Schema `payments`

**`payments`**

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | UUIDv7 | gerado na app (time-ordered, index-friendly) |
| `txid` | varchar(25) | **unique**, alfanumérico |
| `merchant_id` | uuid | herdado da API key, nunca do payload |
| `amount_cents` | bigint | Money |
| `status` | enum | `PENDING, CONFIRMED, PARTIALLY_REFUNDED, REFUNDED, EXPIRED, FAILED` |
| `version` | int | locking otimista |
| `expires_at` | timestamptz | copiado do PSP |
| `end_to_end_id` | varchar | nulo até confirmação |
| `fee_cents`, `net_cents` | bigint | preenchidos na confirmação (D7) |
| `late_confirmation` | boolean | ressurreição (D6) |
| `created_at`, `confirmed_at` | timestamptz | |

Índices: unique(`txid`), parcial `WHERE status='PENDING' AND expires_at < now()`, (`merchant_id`, `created_at DESC`) pra listagem.

**`idempotency_keys`** — `key` unique · `request_fingerprint` (hash do body) · `response_snapshot` (http status + JSONB) · `payment_id` · `state` (`IN_FLIGHT`/`COMPLETED`) · cleanup job > 24h.

**`webhook_events`** — `provider_event_id` (= `endToEndId + tipo`) **unique** (dedupe) · `payload_raw` JSONB **imutável** (replay de parsing) · `status` (`RECEIVED→PROCESSED/IGNORED`) · `signature_valid` · `received_at`.

**`refunds`** — `id` UUIDv7 · `payment_id` · `amount_cents` · `fee_refund_cents` · `net_refund_cents` · `reason` · criado sob lock pessimista do payment.

**`outbox`** — `id` UUIDv7 · `aggregate_id` · `type` · `version` · `payload` JSONB · `request_id` · **ciclo de entrega**: `status` (`PENDING/SENT/FAILED/EXHAUSTED`) · `attempt_count` · `next_attempt_at` (backoff 30s→2min→5min) · `published_at`. `SELECT … FOR UPDATE SKIP LOCKED` no relay.

**`audit_log`** — mínimo: `command_name` · `actor_key_id` · `merchant_id` · `aggregate_id` · `request_id` · `created_at`. O "quem" dos comandos (o "o quê" já vive nos eventos do aggregate e nos webhooks crus).

### 5.2 Schema `ledger`

**`accounts`** — plano de contas minúsculo:

| Conta | Tipo | Representa |
|---|---|---|
| `ASSET:PSP_CLEARING` | Ativo | dinheiro fisicamente na conta do PSP |
| `LIABILITY:MERCHANT:{id}:PENDING` | Passivo | recebido, ainda não liquidado |
| `LIABILITY:MERCHANT:{id}:AVAILABLE` | Passivo | liquidado, disponível |
| `REVENUE:PLATFORM_FEES` | Receita | a taxa da plataforma |

**`journal_entries`** (cabeçalho) — `id` · `event_id` **unique** (idempotência do consumer) · `description` · `occurred_at`.

**`ledger_entries`** (linhas, **append-only**: sem UPDATE/DELETE — nem no código, nem na permissão do banco) — `id` · `journal_id` · `account` · `direction` (`DR`/`CR`) · `amount_cents` · Constraint de aplicação + job auditor: `Σ DR = Σ CR` por journal.

**`balances`** (projection CQRS-lite, D9) — `account` unique · `pending_cents` · `available_cents` — atualizada **na mesma transação** do insert das linhas. Validação `available ≥ refund` usa a projection; a verdade são as linhas (job de prova compara projection vs `SUM`).

### 5.3 Movimentos contábeis (exemplo: R$ 100,00, taxa 100 bps = R$ 1,00)

```
[1] Pagamento confirmado (webhook validado)
    DR ASSET:PSP_CLEARING             100,00
    CR LIABILITY:MERCHANT:m1:PENDING   99,00
    CR REVENUE:PLATFORM_FEES            1,00

[2] Liquidação D+1 (job, clock simulado)
    DR LIABILITY:MERCHANT:m1:PENDING   99,00
    CR LIABILITY:MERCHANT:m1:AVAILABLE 99,00

[3] Devolução parcial de R$ 40,00 (o dinheiro volta ao pagador)
    DR LIABILITY:MERCHANT:m1:AVAILABLE 40,00
    CR ASSET:PSP_CLEARING              40,00

[4] Estorno proporcional da taxa (40% de 1,00 = 0,40) — D8
    DR REVENUE:PLATFORM_FEES            0,40
    CR LIABILITY:MERCHANT:m1:AVAILABLE  0,40

Saldo final do merchant: 59,40 disponível | taxas líquidas: 0,60 | clearing: 60,00 ✓
```

---

## 6. Contratos de API

Base: `/v1` (path versioning, pragmático). Autenticação: `Authorization: Bearer psp_test_…` (D12). Dinheiro em JSON: **inteiro em centavos**. Idempotência nas mutações: header `Idempotency-Key`.

### 6.1 Recursos

| Método & path | Auth | Descrição |
|---|---|---|
| `POST /v1/payments` | API key | Cria cobrança PIX. `201` + `Location` |
| `GET /v1/payments/{txid}` | API key | Detalhe + status + BR Code. Outro merchant → **404** (não 403) |
| `GET /v1/payments?cursor=&limit=` | API key | Histórico, cursor pagination (default 20, máx 100) |
| `POST /v1/payments/{txid}/refunds` | API key | Devolução parcial (`amount` no body) ou total (body vazio) |
| `GET /v1/payments/{txid}/events` | API key | Trilha de eventos do aggregate |
| `POST /webhooks/psp` | HMAC (sem API key) | Intake do PSP, fail-closed |

**Tenancy (D-mão-na-roda):** `merchant_id` vem **só** da API key — nunca de path, query ou body. IDOR morto por design: "o tenant nunca é input do cliente".

### 6.2 Exemplos

```http
POST /v1/payments
Authorization: Bearer psp_test_9f2c…
Idempotency-Key: 4e7a2c10-…
Content-Type: application/json

{ "amount": 10000, "description": "Pedido #123", "expiresIn": "PT30M" }
```

```http
HTTP/1.1 201 Created
Location: /v1/payments/8KD4Z9X2Q7W1M5T3R6Y0A1B2C
X-Request-Id: 7c1e…

{
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "status": "PENDING",
  "amount": 10000,
  "currency": "BRL",
  "expiresAt": "2026-08-28T15:30:00Z",
  "brcode": "00020126580014br.gov.bcb.pix…6304AB12",   ← EMV + CRC16, gerado por nós
  "expiresIn": "PT30M"
}
```

Webhook do PSP (assinado):

```http
POST /webhooks/psp
X-PSP-Timestamp: 1787932800
X-PSP-Signature: hex(HMAC-SHA256(secret, "1787932800" + "." + rawBody))
Content-Type: application/json

{ "eventId": "psp-evt-991", "type": "payment.confirmed",
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C", "endToEndId": "E904038…",
  "amount": 10000, "paidAt": "2026-08-28T15:02:11Z" }
```

### 6.3 Catálogo de erros (RFC 9457 `application/problem+json` + `code`)

Cliente trata por `code`, nunca parseando mensagem. **Um único `ErrorResponseWriter` emite todos** (handler global, filtros de auth/HMAC, entry points) — sem formato por filtro.

| `code` | HTTP | Quando |
|---|---|---|
| `invalid_request` | 400 | Validação (body inclui mapa de campos) |
| `unauthorized` | 401 | API key ausente/inválida |
| `invalid_signature` | 401 | HMAC do webhook não bate |
| `signature_expired` | 401 | Timestamp do webhook fora da janela de 5 min (anti-replay) |
| `payment_not_found` | 404 | txid inexistente ou de outro merchant |
| `idempotency_key_conflict` | 409 | Mesma key + **body diferente** (inegociável) |
| `payment_not_refundable` | 409 | Status não permite refund |
| `refund_exceeds_remaining` | 409 | `Σ refunds + amount > original` |
| `invalid_transition` | 409 | Transição de estado ilegal |
| `idempotency_key_in_flight` | **425** + `Retry-After` | Retry chegou enquanto a 1ª request processa (D18) |
| `internal` | 500 | Loga method+URI+exception; **nunca vaza mensagem interna** |

Detalhe de protocolo: `NoResourceFoundException` → 404 canônico (nunca 500).

### 6.4 Cursor pagination

`cursor` opaco = base64(`txid` + `created_at` da última página); resposta carrega `nextCursor`; ordenação fixa `created_at DESC, txid DESC` (estável sob inserção).

---

## 7. Eventos e Mensageria

### 7.1 Envelope do evento (o contrato que é nosso — broker nenhum vaza pra ele)

```json
{
  "eventId": "0198f6a2-…",        // UUIDv7 — base do dedupe do consumer
  "type": "payment.confirmed",    // catálogo versionado
  "version": 1,
  "aggregateId": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "merchantId": "…",
  "requestId": "7c1e…",
  "occurredAt": "2026-08-28T15:02:12Z",
  "payload": { "amount": 10000, "fee": 100, "net": 9900, "late": false }
}
```

### 7.2 Catálogo

| Tipo | Producer | Consumidores | Nota |
|---|---|---|---|
| `payment.created` | payments | ledger (nada a lançar ainda — registro), notifications | cob criada, PENDING |
| `payment.confirmed` | payments | **ledger** (lançamento [1]), notifications | carrega breakdown amount/fee/net + flag `late` |
| `payment.expired` | payments | notifications | — |
| `payment.failed` | payments | notifications | PSP esgotou (D19) |
| `refund.created` | payments | **ledger** (lançamentos [3]+[4]), notifications | carrega amount/feeRefund/netRefund |

### 7.3 Topologia (LocalStack)

```
SNS topic:  payment-events.fifo            (MessageGroupId = txid → ordenação por pagamento)
   ├── SQS ledger-events.fifo              (subscription filter: tipos do ledger)
   │      └── SQS ledger-events-dlq.fifo   (redrive: maxReceiveCount=5)
   └── SQS notification-events.fifo
          └── SQS notification-events-dlq.fifo
```

- **Ordenação**: `MessageGroupId = txid` — análogo exato de particionar Kafka por key
- **At-least-once**: duplicação é normal; **consumer idempotente por `eventId`** é lei (`event_id` unique no journal; dedupe no notifications)
- **DLQ** por fila com redrive policy; visibility timeout calibrado > pior tempo de processamento
- **Provisioning**: tópicos/filas/filters criados no startup da app via AWS SDK (compose autocontido, zero script manual)
- LocalStack **Community** cobre SNS/SQS; Testcontainers tem módulo próprio pra testes

### 7.4 Outbox (a ponte transacional)

1. Serviço de use case grava agregado **e** linha do outbox na **mesma transação**
2. Relay (`@Scheduled`, N workers): `SELECT … FOR UPDATE SKIP LOCKED` das `PENDING` com `next_attempt_at <= now()` → publica no SNS via port `EventPublisher` → marca `SENT`
3. Falha de publicação → `FAILED`, `attempt_count++`, backoff `30s → 2min → 5min`; após N tentativas → **`EXHAUSTED`** (para de ser polido)
4. **Requeue administrativo**: endpoint (auditado) volta `EXHAUSTED`/`FAILED` pra `PENDING`
5. Publicou-e-morreu-antes-de-marcar → duplicata na fila → **ok por design** (consumer idempotente)
6. **Republish tool**: republicar eventos da outbox por período/agregado = nosso "replay" (substitui replay de offsets do Kafka)

### 7.5 Portabilidade de broker (D3/D4)

| Preocupação | SQS/SNS (v1) | Kafka | RabbitMQ |
|---|---|---|---|
| Ordenação | `MessageGroupId` | record key | consistent-hash exchange |
| DLQ | redrive policy nativa | DLQ topic | dead-letter exchange |
| Retry | visibility timeout | retry local | nack/requeue |
| Replay | republish da outbox | offsets | não nativo |

Ports: `EventPublisher` (relay chama) + handler de eventos por consumer. Envelope, dedupe e semânticas são nossos; cada adapter de broker encapsula o específico (**Channel Adapter**, Hohpe & Woolf). Trocar de broker = tocar o módulo de mensageria apenas. **Spring Application Events não é o barramento** (comunicação in-JVM esconderia o custo distribuído).

---

## 8. Segurança

### 8.1 API keys (D12 — estilo Stripe)

- Formato `psp_test_<43 chars base62>`; **prefixo indexável** pra lookup, hash **SHA-256 no banco** (nunca a chave crua); comparação **constant-time**
- Ligada a um merchant; `merchant_id` **só** vem da credencial (§6.1); 404 (não 403) pra recurso de outro merchant
- Spring Security 7 com **um filter custom apenas** — sem OAuth server (cerimônia sem uso)

### 8.2 Webhook do PSP (fail-closed)

- `HMAC-SHA256(timestamp + "." + rawBody)` nos headers `X-PSP-Timestamp`/`X-PSP-Signature`
- **Anti-replay**: timestamp > 5 min no passado → `signature_expired` (401). Efeito colateral desejado: webhook atrasado além da janela é **legitimamente rejeitado — e o reconciler confirma** (segurança e resiliência se provando no mesmo teste)
- Route `permitAll` na camada HTTP **somente** com validação no filtro (padrão as-built do flowtxt); payload cru salvo **sempre**, inclusive de assinatura inválida (auditoria de ataque)
- Atrás do NGINX: a app deve ver a mesma URL que o PSP discou + forwarded headers configurados no profile de produção

### 8.3 Postura PCI e produção

- Nunca armazenar dado sensível de pagamento — só tokens do PSP (PIX nem expõe isso; cartão-stretch seguiria a regra: token + últimos 4)
- **`ConfigValidator` fail-fast agregado**: boot aborta listando TODOS os problemas (placeholders não resolvidos, segredos curtos, chaves AWS estáticas em prod)
- **Lockdown de produção provado por IT**: Swagger/api-docs fora, actuator health-only com `show-details: never`, porta de management isolada, API key obrigatória nos endpoints de negócio

---

## 9. Observabilidade

| Pilar | Decisão |
|---|---|
| **Logs** | JSON estruturado **built-in do Boot 4** (profile ECS, zero dependência). Campos em toda linha: `request_id`, `payment_id`/`aggregate_id`, `merchant_id` |
| **Correlação** | Filtro `X-Request-Id`: aceita (charset/length validados), gera se ausente, **ecoa na resposta**, propaga pro MDC e pro outbox |
| **Métricas** | Micrometer + `/actuator/prometheus`: (1) pagamentos por transição de status, (2) **outbox lag** (idade do evento mais antigo não-publicado — *a* métrica da arquitetura), (3) profundidade das DLQs, (4) reconciliações que confirmaram pagamento (mede webhooks perdidos!), (5) falhas de assinatura de webhook, (6) tentativas do outbox por status |
| **Health** | Liveness/readiness separados; readiness gated em Postgres + SNS/SQS (LocalStack); actuator com exposure mínimo |
| **Tracing** | Fora (monólito + correlation id); stretch se extrair serviços |

---

## 10. Estratégia de Testes

### 10.1 Pirâmide (inclinada pra integração — escolha consciente)

| Camada | Ferramenta | O que cobre |
|---|---|---|
| Unidade pura (sem Spring) | JUnit 6 + AssertJ | Máquina de estados (tabela de permitidas/proibidas), `Money`, taxa + estorno proporcional, BR Code EMV + CRC16, txid, VOs |
| Integração | **Testcontainers 2.0** (Postgres + LocalStack singleton, `@ServiceConnection`) + **WireMock** (PSP stubado, `wiremock-spring-boot`) | Todas as costuras de verdade |
| E2E | 2–3 suítes com monólito + psp-simulator reais | Montagem completa ponta a ponta |

**Regra de ouro do mock:** só o mundo externo (PSP via WireMock). **Nunca** mock do próprio banco, da fila, do outbox. `RestTestClient` (Framework 7) nos testes HTTP; `Clock` injetado como bean (viagem no tempo, zero sleeps); **Awaitility** pra tudo eventual; corridas determinísticas com `ExecutorService` + `CyclicBarrier`; suítes `@Tag("chaos")`/`@Tag("stress")` em job separado; containers singleton (um Postgres/LocalStack pra suíte toda).

### 10.2 Catálogo de cenários (cada teste guarda uma garantia de dinheiro/corrida)

**Idempotência**
1. Mesma key + mesmo body → mesma resposta, **um** pagamento no banco
2. Mesma key + body diferente → `409`
3. Key in-flight → `425` + `Retry-After`
4. Retry após sucesso → snapshot devolvido, zero efeito colateral

**Webhooks**
5. HMAC inválido → rejeitado + payload cru salvo
6. Duplicado (`endToEndId`+tipo) → processado uma vez
7. Fora de ordem/tardio → estados finais consistentes
8. **Replay do `payload_raw`** → mesmo resultado
9. **Cenário-assinatura**: expiração e confirmação simultâneos → ressurreição exatamente uma vez + auditoria

**Corridas**
10. Dois refunds concorrentes de 60% cada → um passa, outro falha elegante, invariante preservada
11. N threads no mesmo UPDATE condicional → exatamente um vence
12. Relay `SKIP LOCKED` em paralelo → sem publicação dupla por worker

**Outbox/eventos**
13. Relay "morto" → evento pendente; relay volta → publica (at-least-once na prática)
14. Publicou-e-morreu → duplicata na fila → consumer dedupa por `eventId`
15. Poison message → DLQ com contagem auditável
16. Backoff → `FAILED` → `EXHAUSTED` → requeue admin → `SENT`

**Ledger**
17. Todo lançamento fecha (`Σ DR = Σ CR`) — invariante checada após cada cenário
18. **Property test (jqwik)**: sequência aleatória de pagamentos/refunds de valores variados → `projection == SUM(das linhas)` sempre
19. Refund além do disponível → rejeitado, saldo intacto

**Caos PSP (WireMock)**
20. Timeout na criação → retry com backoff → `FAILED` só após esgotar
21. **Webhook nunca chega → reconciler consulta e confirma sozinho** (a alma do projeto)
22. Webhook atrasado além da janela → rejeitado → reconciler confirma

### 10.3 Ordem e disciplina

TDD no domínio puro primeiro (máquina de estados, Money, BR Code) → integração logo após cada costura de pé → caos/corridas por último. Nomes de teste como especificação (`refundo_concorrente_alem_do_saldo_e_rejeitado_e_saldo_fica_consistente`). Cobertura com **piso por módulo** (line+branch), medida **após** os ITs (combinada com unidade). Failsafe: `./mvnw verify` = unidade + `*IT`; `-Dskip.unit.tests=true` pra rodar só ITs.

---

## 11. CI/CD & Release Engineering (GitHub Actions)

### 11.1 Pipeline (commit → sempre o mesmo jar + imagem)

```
build (gate do PR)
├── ./mvnw test                        → unidade pura
├── ArchUnit + scripts/check-boundaries.sh  → fronteiras duplas (semântica + import/FQN)
├── ./mvnw spotbugs:check              → 0 bugs
├── OWASP Dependency-Check             → NVD em cache + NVD_API_KEY; report-only, degrada pro cache
├── ./mvnw test -Dtest='*IT'           → Testcontainers (Postgres + LocalStack + WireMock)
├── ./mvnw jacoco:check                → cobertura combinada unidade+IT, piso por módulo
└── clean package + artifact do jar    → consumido pelos jobs abaixo

image (needs: build)
├── docker build (tag = git SHA) + registro do digest imutável
├── gate non-root (docker run … id → falha se uid=0)
├── Trivy 2-pass: SARIF → Security tab (advisory) + table HIGH/CRITICAL corrigível → gate
└── SBOM CycloneDX + digest → artifacts

security (paralelo)
├── CodeQL (SAST Java)
└── Dependency Review no PR (vuln/licença de dependência nova → bloqueia)

runtime-smoke (needs: build — GATES o pipeline)
├── compose up (postgres + localstack + psp-simulator) → readiness polling
├── jar de produção + Flyway migrate
├── E2E happy path: criar → pagar no simulador → webhook → CONFIRMED → ledger lançou
├── caos: webhook suprimido → reconciler confirma          ← nossa assinatura no CI
└── graceful shutdown sob carga (drain) — regressão quebra o CI

performance (continue-on-error, consultativo)
└── k6 budgets-as-code → POST /payments p95<250ms · GET /payments/{txid} p95<100ms · webhook p95<150ms
    (promover a gate duro = decisão deliberada após calibrar 2–3 runs)

release (tag anotada vX.Y.Z)
└── todos os gates no commit tagado → imagem semver + GitHub Release com jar + SBOM da imagem exata
```

Actions de 1ª parte por versão; de terceiros **pinadas por SHA de commit**. Dependabot ativo desde M0. Política de gates documentada em `docs/ci-vulnerability-gates.md`.

### 11.2 Deploy blue-green on-premises (sem k8s)

| Elemento | Decisão |
|---|---|
| Topologia | `api-blue` :8081 / `api-green` :8082 + NGINX :8080 (`nginx:1.27-alpine`) |
| Fluxo | nova versão no slot inativo → readiness gate → **canary 10% com 30s de observação** → cutover → derruba slot velho; abort automático pro blue em qualquer sinal ruim |
| Rollback | instantâneo (`rollback.sh` re-flipa upstream) |
| Weights | cópia runtime do conf + `nginx -s reload` — template versionado **nunca** mutado |
| Gotchas já aprendidos (de graça) | `down` em vez de `weight=0` (não existe → crash-loopou o LB de outrem); `resolver 127.0.0.11 valid=10s` + `zone` + `resolve` nos upstreams pra pegar frotas recriadas sem reload; `proxy_next_upstream error timeout`; checagens passivas `max_fails=3 fail_timeout=10s`; `keepalive 32` |
| Imagem | multi-stage, **layered jar**, base **pinada por digest**, non-root, root FS read-only + tmpfs, limites CPU/RAM, healthcheck com start period |
| Shutdown | `server.shutdown=graceful` + timeout por fase de shutdown |
| Deploy | **sempre por tag imutável**; rollback = redeploy da tag anterior |
| Migrations | **expand/contract** (blue e green compartilham o Postgres): adiciona coluna numa release, remove na seguinte — nunca rename/migração destrutiva na release que passa a usar |
| Schedulers | **sem ShedLock**: overlap breve de 2 instâncias é inofensivo por design (UPDATE condicional, `SKIP LOCKED`, unique constraints) |
| Backup | `pg_dump` agendado + **drill de restore testado com evidência** no runbook (Postgres é a verdade; LocalStack é descartável) |

---

## 12. Runtime On-Premises (Compose)

| Serviço | Imagem | Porta | Nota |
|---|---|---|---|
| `nginx` | nginx:1.27-alpine | 8080 | LB blue-green |
| `api-blue` | nossa (tag SHA) | 8081 | perfil prod |
| `api-green` | nossa (tag SHA) | 8082 | slot inativo por default |
| `psp-simulator` | nossa | 8090 | PSP + banco do pagador + caos |
| `postgres` | postgres:16-alpine | 5432 | volume nomeado; fonte da verdade |
| `localstack` | localstack/localstack | 4566 | SNS+SQS; **sem persistência** (descartável por design) |

Contrato de ambiente via `.env.example` (twelve-factor): `DATABASE_…`, `AWS_ENDPOINT_URL`, `PSP_BASE_URL`, `PSP_WEBHOOK_SECRET`, `PAYMENTS_FEE_BPS`, `CHAOS_*` (simulador), `SERVER_SHUTDOWN…`. **Fontes 100% em inglês** (identifiers, comments, logs).

**Knobs de caos do psp-simulator** (env): `CHAOS_WEBHOOK_DUPLICATE` (duplica), `CHAOS_WEBHOOK_DELAY_MS`, `CHAOS_WEBHOOK_DROP` (probabilidade de "esquecer"), `CHAOS_PSP_LATENCY_MS`, `CHAOS_PSP_ERROR_RATE`. O simulador expõe: `POST /cobs`, `GET /cobs/{txid}`, `POST /cobs/{txid}/pagamentos` (o "banco do pagador" paga o QR → dispara webhook assinado), `GET /health`.

---

## 13. Roadmap de Entrega

Cada milestone fecha com: testes verdes no pipeline completo, **matriz de aceitação preenchida** (requisito → implementação → teste → evidência), release notes + CHANGELOG, lições registradas. Desvios residuais **declarados** com dono.

| M | Nome | Escopo | Critérios-chave de aceitação (matriz completa no repo) |
|---|---|---|---|
| **M0** | Esqueleto | Maven multi-módulo + módulos vazios com ArchUnit passando, compose (postgres/localstack/nginx/psp-simulator stub), CI rodando gates de build, Flyway por schema, provisioning de filas no startup, AGENTS.md + docs base | CI verde num PR real; ArchUnit reprova import ilegal (teste comprovando o gate) |
| **M1** | Happy path | Criar cob (PSP + BR Code) → PENDING → webhook → CONFIRMED; idempotência completa; API key; erros canônicos | Catálogo 1–5 verdes; duas requests idênticas → 1 pagamento; webhook duplicado → 1 confirmação |
| **M2** | Eventos | Outbox + relay + SNS/SQS FIFO; ledger consumindo (lançamento [1]); projection de saldo; notifications básico | Catálogo 13–14, 17–18 verdes; duplicata na fila → 1 lançamento; `ΣDR=ΣCR` após cada cenário |
| **M3** | Sofrimento | Refunds (parcial/total/concorrentes), expiração, ressurreição, reconciliador, settlement D+1, DLQ + backoff + EXHAUSTED + requeue | Catálogo 6–12, 15–16, 19–22 verdes; cenário-assinatura (9) e reconciler (21) no CI |
| **M4** | Acabamento | Métricas + logs JSON + correlação; blue-green com canary + rollback; runtime-smoke no CI; release por tag + SBOM; README com diagrama + ADRs finais; matriz de aceitação completa; drill de restore | Deploy v1→v2 sem downtime comprovado; rollback instantâneo exercido; GitHub Release com SBOM |
| **M5** | Stretch | Cartão simulado (2ª Strategy), k6 como gate duro, Redis cache de leitura, reprocessar webhooks via admin | Cartão adicionado **sem tocar** no domínio PIX (prova da abstração) |

---

## 14. Riscos e Mitigações

| Risco | Impacto | Mitigação |
|---|---|---|
| Scope creep (cartão/Redis/k8s cedo) | Projeto eternamente inacabado | Não-metas §1.2; card/Redis travados em M5 |
| Quirks do LocalStack (SNS→SQS FIFO, assinaturas) | Surpresas na integração | Testcontainers desde M0; provisionamento idempotente no startup; envelope próprio reduz área de contato |
| CI lento (Testcontainers + 6 jobs) | PRs lentos, suíte evitada | Containers singleton; gates rápidos primeiro; chaos/stress taggados em job separado; cache NVD/Trivy |
| Migrations quebram blue-green | Deploy com downtime | Expand/contract obrigatório (D16); smoke de migração no runtime-smoke |
| Duplicação de eventos em produção local | Dinheiro lançado 2x | At-least-once assumido; consumer idempotente **por design** e testado (cenário 14) |
| Relógio do host vs janela HMAC | Webhooks legítimos rejeitados | Janela de 5 min + reconciler como rede; clock do host monitorado no health |
| Sega segredo HMAC/API key no repo | Comprometimento total | Segredos só por env; ConfigValidator barra segredo default em prod; `.dockerignore`/`.gitignore` de segredos |
| Documentação apodrecendo | Portfólio perde valor | Docs como critério de DoD do milestone; matriz de aceitação obrigatória pra fechar M |

---

## 15. Registro de ADRs (índice)

Arquivos completos em `docs/adr/` a partir do M0. Decisão → alternativa rejeitada → consequência.

| ADR | Título (decisão em uma linha) |
|---|---|
| 0001 | Monólito modular com costuras de extração, em vez de microserviços desde o início |
| 0002 | Hexagonal com domínio rico; split Maven por bounded context (jamais por camada) |
| 0003 | SNS/SQS FIFO (LocalStack) via SDK v2 direto, em vez de Kafka |
| 0004 | Envelope de evento próprio + Channel Adapters pra portabilidade de broker |
| 0005 | Outbox transacional com relay `SKIP LOCKED` hand-rolled (vs Debezium, vs Spring Modulith) |
| 0006 | PIX QR dinâmico como único método do core; cartão como Strategy futura |
| 0007 | Transições de estado via UPDATE condicional + locking otimista (o banco é o árbitro) |
| 0008 | Ressurreição de pagamento expirado confiando no PSP, com auditoria |
| 0009 | Taxa calculada no payments (bps); evento carrega breakdown; ledger contábil burro |
| 0010 | Devolução de taxa proporcional no refund (estorno de receita) |
| 0011 | Saldo como projection transacional CQRS-lite, com job de prova contra as linhas |
| 0012 | Sem saques no v1; refunds drenam `AVAILABLE` |
| 0013 | API key Stripe-style em vez de JWT/OAuth2 |
| 0014 | Migrations forward-only com expand/contract (zero rollback scripts) |
| 0015 | Postgres fonte da verdade; LocalStack descartável com republish da outbox como replay |
| 0016 | Blue-green NGINX com canary on-premises, sem k8s e sem ShedLock |
| 0017 | Release por tag imutável com SBOM; deploy nunca por tag moving |

---

## Apêndice A — Glossário

| Termo | Significado |
|---|---|
| **PSP** | Payment Service Provider — provedor de infraestrutura de pagamento (Stripe, Efí…) |
| **txid** | Identificador da cobrança definido pelo recebedor (máx 25 alfanuméricos) |
| **endToEndId** | Identificador fim-a-fim do PIX na rede (gerado pelo PSP, imutável) |
| **BR Code** | QR Code PIX (payload EMV-QRCPS-MPM com CRC16-CCITT) |
| **Outbox** | Padrão: evento gravado na mesma transação do agregado, publicado depois por um relay |
| **Dupla entrada** | Contabilidade onde todo lançamento tem débitos = créditos (journal + entries) |
| **Projection** | Read model derivado dos eventos, aqui mantido na mesma transação do consumer |
| **Channel Adapter** | Padrão (Hohpe & Woolf): adapter que encena o específico de um middleware sob um port próprio |
| **DLQ** | Dead-letter queue — destino de mensagens que falharam além do limite |
| **Expand/contract** | Disciplina de migration compatível com N e N+1 versões da app simultâneas |
| **Blue-green** | Duas frotas idênticas; o LB decide qual recebe tráfego; deploy = flip |
| **Canary** | Fração pequena do tráfego pra nova versão antes do cutover total |

## Apêndice B — Créditos de inspiração

Padrões e hábitos roubados conscientemente (caderno completo: `docs/ideias-para-roubar.md`):

- **[ecommerce](https://github.com/daniel-castilho/ecommerce)** — cultura de docs (lessons/release notes/playbook/AGENTS.md), ciclo SENT/FAILED/EXHAUSTED + requeue, ArchUnit em tudo
- **[spotpobre](https://github.com/daniel-castilho/spotpobre)** — pipeline de CI 4 jobs (Trivy 2-pass, SBOM, non-root, shutdown-under-load), blue-green com canary + gotchas de NGINX/Docker, matriz de aceitação com evidência, IT de lockdown de produção, k6 consultativo
- **[flowtxt](https://github.com/daniel-castilho/flowtxt)** — ErrorResponseWriter canônico único, domínio rico as-built, JaCoCo por módulo, release em 3 tiers, ConfigValidator agregado, CodeQL + Dependency Review

## Apêndice C — Histórico do documento

| Versão | Data | Mudanças |
|---|---|---|
| 1.0.1 | 2026-08-28 | Batismo oficial: **Dargent** (D22) — varredura de rename em todos os artefatos (package `io.dargent`, métricas `dargent_*`, `DARGENT_API_KEY`, imagens `dargent-api`) |
| 1.0.0 | 2026-08-28 | Versão inicial — consolidação integral do brainstorm (arquitetura, domínio, dados, API, eventos, segurança, testes, CI/CD, runtime, roadmap, riscos, ADRs) |
