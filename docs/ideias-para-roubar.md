# 💡 Ideias para Roubar — Caderno de Inspiração

> Projetos de referência analisados (mesmo autor, daniel-castilho):
> 1. **[ecommerce](https://github.com/daniel-castilho/ecommerce)** — modular monólito Jakarta EE 11 + Open Liberty, hexagonal, Postgres + Flyway
> 2. **[spotpobre](https://github.com/daniel-castilho/spotpobre)** — backend de streaming Java 21 + Spring Boot, Clean Architecture, DynamoDB + S3 + Redis, LocalStack on-premises, NGINX blue-green
> 3. **[flowtxt](https://github.com/daniel-castilho/flowtxt)** — backend de entrega de SMS Java 21 + **Spring Boot 4.1**, Clean Architecture em camadas, Twilio + Postgres + Redis — o autor aplicou nele o "plano de excelência" do spotpobre
>
> Status: ☐ proposto · ✅ adotado · ❌ rejeitado (com motivo)

---

## 1. Do ecommerce (Jakarta EE)

### Processo e documentação — o maior ROI, custo zero
| Ideia | Como aplicamos | Status |
|---|---|---|
| `docs/lessons.md` — diário de lições amargas e regras de ouro, alimentado a cada release | Criar no 1º milestone; cada bug difícil vira uma lição numerada. Conteúdo pronto pra entrevista | ☐ |
| Release notes por versão + `CHANGELOG.md` | Um arquivo por milestone (M0…Mn) em `docs/releases/` | ☐ |
| `docs/testing-playbook.md` com smoke de pré-release | Pirâmide, catálogo de cenários, smoke de pré-tag com provas SQL read-only | ☐ |
| `tasks/` com backlogs e specs dentro do repo | Specs por milestone + backlog priorizado (P0/P1/P2) | ☐ |
| `AGENTS.md` — regras para contribuidores humanos e agentes de IA | Fronteiras de módulo, convenções, o que nunca fazer | ☐ |

### Padrões técnicos (validam nosso desenho — chegaram lá independentemente)
| Ideia | Como aplicamos | Status |
|---|---|---|
| Regra cross-module: "nunca depender do adapter de outro módulo, só do `domain.port`" + `shared-kernel` sem deps | Já era nossa lei; o repo confirma | ✅ (já no design) |
| Outbox transacional com **claim**, idempotency key (`EVENT:{orderId}`) e snapshot da mensagem | Nosso relay com `SKIP LOCKED`; snapshot do payload no outbox | ✅ (já no design) |
| **Ciclo de entrega completo do outbox:** `SENT / FAILED / EXHAUSTED` + contagem de tentativas + backoff (30s→2min→5min) + **requeue administrativo auditado** | Adotar no relay e no módulo notifications: falha N vezes → EXHAUSTED, para de ser polido; endpoint de requeue | ☐ |
| `Money` como value object no shared-kernel | Nosso é `cents: Long` + moeda (BRL-only); princípio idêntico | ✅ (já no design) |
| Pessimistic lock para serializar operação concorrente de dinheiro | Refunds com `SELECT FOR UPDATE` no payment — espelho do lock de cupom deles | ✅ (já no design) |
| ArchUnit em todos os módulos | Gate de fronteira no CI | ✅ (já no design) |
| Audit log com ator | Versão mínima nossa: `actor` (API key id) nos eventos do aggregate + tabela `audit_log` de comandos | ☐ |

### O que NÃO copiar
| Item | Motivo |
|---|---|
| Open Liberty + Jakarta EE + JSF/MyFaces + EclipseLink | Stack de outra era; Boot embutido entrega o mesmo com 10% do peso |
| Migrations "with rollbacks" | Nós: **forward-only + expand/contract** (blue-green compartilha o Postgres). Rollback script apodrece; rollback de verdade = release anterior sobre schema compatível |
| Postgres FTS, OpenPDF, Redis (deles) | Fora do nosso escopo |

### Posicionamento
- O roadmap deles admite: *"Real payment providers (currently mocked)"* → **nosso projeto é exatamente a peça que falta** no e-commerce deles (e em quase todos). Story de pitch pronta: "o que acontece depois do checkout?"

---

## 2. Do spotpobre (Java 21 + Spring Boot — nosso gêmeo)

### 🔥 CI no GitHub Actions — o blueprint do nosso pipeline
Pipeline em 4 jobs (`build → [image, runtime-smoke, performance]`), todos os detalhes em `.github/workflows/ci.yml`:

| Etapa deles | Detalhe que vale ouro | Adotar? |
|---|---|---|
| `mvnw test` (unidade) | — | ✅ |
| `scripts/check-boundaries.sh` | Gate de fronteira por import + FQN no CI, **complementa** ArchUnit — pegou leak real (classe de redaction em `infrastructure/common` usada por application → movida pra domain) | ☐ |
| `mvnw spotbugs:check` | SpotBugs com exigência de 0 bugs | ☐ |
| OWASP Dependency-Check | **Cache do espelho NVD** (~380k registros, key versionada) + `NVD_API_KEY` em secret; política report-only (`failBuildOnCVSS=11`), degrada pro cache se NVD cair — CI não fica refém da NVD | ☐ |
| `mvnw test -Dtest='*IT'` | Testcontainers (Docker no runner) | ✅ |
| **JaCoCo DEPOIS dos ITs** | Exec file combina unidade + IT — comentário do pipeline: "unit-only mede ~54% e distorce o que a suíte realmente exercita" | ☐ |
| `clean package` + upload do jar como artifact | Jobs downstream baixam o jar de produção de verdade | ✅ |
| **Job `image`:** build com tag = SHA + **gate non-root** (`docker run … id` → falha se `uid=0`) + **Trivy em duas passadas** (SARIF advisory → Security tab; table HIGH/CRITICAL corrigível → exit 1) + **SBOM CycloneDX** + digest da imagem registrado pra deploy/rollback imutável | Supply chain completa; quirks do trivy-action documentados inline (SARIF ignora filtro de severidade — issue #309) | ☐ |
| **Job `runtime-smoke` (GATES o pipeline):** compose com LocalStack → polling de readiness via AWS CLI → seed → **jar de produção rodando** → **graceful shutdown sob carga** | Regressão de drain derruba o CI. PRA NÓS: é a propriedade crítica do blue-green | ☐ |
| **Job `performance` (continue-on-error):** k6 com **budgets-as-code** (p95: /me < 150ms, lista < 250ms, busca < 350ms), warm-up, resumos JSON como artifacts | "Consultativo agora; promover a gate duro é decisão deliberada após calibrar 2–3 runs" — honestidade metodológica rara | ☐ |
| Actions de terceiros pinadas por SHA de commit (trivy-action), de 1ª parte por versão | Higiene de supply chain no próprio workflow | ☐ |

**Nosso runtime-smoke vai além do deles:** compose com Postgres + LocalStack + psp-simulator → happy path E2E completo (criar → pagar no simulador → confirmado via webhook) → **cenário de caos: webhook "esquecido" → reconciler confirma** → shutdown sob carga.

### 🔥 Blue-green on-premises — exatamente o nosso plano, já as-built com lições caras
De `docker-compose.bluegreen.yml`, `bluegreen-deploy.sh`, `bluegreen-rollback.sh`, `nginx-bluegreen.conf`:

| Detalhe as-built | Por que importa pra nós |
|---|---|
| blue (8081) / green (8082) + NGINX + LocalStack; **readiness gate → canary 10% com 30s de observação + abort automático pra blue → cutover** | Fluxo de deploy completo com rollback instantâneo (`bluegreen-rollback.sh`) | ☐ |
| Weights de upstream via **cópia runtime do conf + reload**; template versionado nunca mutado | Operação limpa e auditável | ☐ |
| **`down` em vez de `weight=0`** (weight=0 não existe → crash-loopou o LB deles) | Bug real documentado; lição gratuita | ☐ |
| **DNS re-resolution runtime**: `resolver 127.0.0.11 valid=10s` + `zone` + `resolve` nos upstreams → frotas recriadas são pegar sem reload | O gotcha que estraga blue-green em Docker Compose | ☐ |
| `proxy_next_upstream error timeout` + checagens passivas `max_fails=3 fail_timeout=10s` + `keepalive 32` | Resiliência do LB sem k8s | ☐ |
| Containers: **non-root, root FS read-only + tmpfs, limites de CPU/RAM, health checks com start period, `depends_on: service_healthy`** | Hardening de runtime inteiro copiável | ☐ |
| Exercício de release registrado com provas: canary, cutover, rollback, resiliência a troca de IP — todos PASS | A cultura de **provar** o deploy, não só scriptá-lo | ☐ |

### 🔥 Provas de produção e governança
| Ideia | Como aplicamos | Status |
|---|---|---|
| **`ProductionExposureFlowIT`** — IT que sobe o profile de produção real e **prova**: Swagger/api-docs fora, actuator health-only, porta de management trancada | Nossa versão: Swagger fora em prod, actuator com exposure mínimo, API key obrigatória — provado por IT, não por README | ☐ |
| `ProdConfigValidator` — fail-fast em config faltante e chaves estáticas em prod | Validar na boot: endpoints, segredos, profile | ☐ |
| **Matriz de aceitação** (`tasks/p0-acceptance-matrix.md`): requisito → implementação → teste → evidência, com **"declared deviations"** — ledger honesto de desvios residuais | Por milestone: tabela de critério → teste que prova → evidência; desvios declarados com dono | ☐ |
| Decisões humanas registradas (`docs/data-model-decisions.md`) + ADRs | Nossos ADRs já planejados; o formato "decisão humana registrada" reforça | ✅ (já no design) |
| `RequestCorrelationFilter`: requestId no MDC, `X-Request-Id` validado (charset/length seguros), ecoado na resposta, JSON profile no log | Exatamente nosso plano — referência as-built | ✅ (já no design) |
| Correção de protocolo: `NoResourceFoundException` → 404 canônico (não 500) | Item de acabamento pro nosso ProblemDetail | ☐ |
| Dependabot ativo (PRs de bump documentados) | Ativar desde o M0 | ☐ |

### 🔥 Durabilidade do LocalStack on-prem — e a nuance estrategicamente importante
Eles rodam **DynamoDB em LocalStack como fonte da verdade** → precisaram de toda uma máquina de durabilidade: snapshot a cada 15min via systemd timer, restore **verificado** (manifest por tabela, `exit 3` se contagem não bate — "tráfego nunca volta sobre estado não-verificado"), declaração de RPO, **drill trimestral de restore com evidência registrada**.

**Nós NÃO precisamos disso** — e é uma vitória de design: nossa fonte da verdade é o **Postgres**; o LocalStack só carrega filas descartáveis (at-least-once + consumers idempotentes + **republish da outbox** = nosso replay). A máquina de durabilidade deles inteira colapsa pra nós num `pg_dump` + drill de restore.

| Ideia | Como aplicamos | Status |
|---|---|---|
| Drill de restore com evidência (mindset, não a máquina) | Script de backup do Postgres + procedimento de restore **testado** com prova registrada no runbook | ☐ |
| Runbook de release/deploy (`docs/release-runbook.md`) com RPO/duties | Nosso runbook blue-green: deploy, rollback, backup/restore, duties operacionais | ☐ |

### Performance
| Ideia | Como aplicamos | Status |
|---|---|---|
| k6 budgets-as-code, job separado consultativo → gate duro após calibração | Budgets iniciais: `POST /payments` p95 < 250ms, `GET /payments/{id}` p95 < 100ms, intake de webhook < 150ms. "Tripwires de overhead de infra, não resultados de capacidade" | ☐ (stretch) |

### O que NÃO copiar
| Item | Motivo |
|---|---|
| DynamoDB (single-table, GSIs, lease-CAS de upload) | Persistência deles; nosso Postgres relacional é central ao desenho (locks, outbox, ledger) |
| Redis como cache de auth + token bucket rate-limit | Redis é stretch pra nós; a lição do bug deles (serializer não round-trippava → 401 em cache hit) fica anotada pro dia que entrarmos |
| Máquina de snapshot do LocalStack | Substituída pelo Postgres como fonte da verdade + republish da outbox |
| ADR de ECS/Fargate/CodeDeploy | Sem cloud, por decisão nossa |

---

## 3. Do flowtxt (Spring Boot 4.1 + Twilio — o primo direto do nosso problema)

Twilio é um PSP de SMS: API síncrona + **callbacks de status assíncronos assinados** — o mesmo esqueleto do nosso PSP PIX. Tudo abaixo é as-built em Boot 4.1.

### Domínio rico e imutável (o padrão-ouro pro nosso `payments`)
| Ideia | Como aplicamos | Status |
|---|---|---|
| **Entidade com comportamento que guarda o próprio lifecycle forward-only** — transição inválida lança e vira HTTP 409; zero setters no domínio | `Payment` protege `PENDING→CONFIRMED→REFUND_*`; factory methods, igualdade por identidade | ✅ (era nosso desenho; agora com referência as-built) |
| **Value objects que se auto-validam** (`PhoneNumber` exige E.164 estrito) | `Money`, `Txid` (25 alfanum.), `EndToEndId` se auto-validam no construtor | ☐ (reforço) |
| **Typed domain exceptions** (`NotFoundException`→404, `ConflictException`→409, `ForbiddenException`→403) mapeadas centralmente | Nossas exceções de domínio → ProblemDetail num handler único | ☐ |
| Provider failure transiciona a mensagem pra `FAILED` | PSP timeout esgotado → pagamento falha limpa (decisão já cravada) | ✅ (já no design) |
| Status desconhecido do webhook → mapeia pra `UNKNOWN`, nunca quebra | Nosso webhook intake: tipo de evento desconhecido → `IGNORED` + raw salvo | ✅ (já no design) |

### Contrato de erro canônico — o roubo mais concreto do repo
| Ideia | Como aplicamos | Status |
|---|---|---|
| **Um único `ErrorResponseWriter` compartilhado por TODOS os emissores de erro**: GlobalExceptionHandler, entry point de auth, access denied handler, filtro de assinatura, filtro de rate limit — o mesmo envelope em qualquer caminho de erro | Nossos filtros (API key, HMAC webhook) e handlers emitem o **mesmo `problem+json`** via um writer único. Sem isso, cada filtro inventa um formato | ☐ |
| 500 loga method+URI+exception e **nunca vaza a mensagem interna** | Regra no nosso handler raiz | ☐ |
| `SecurityConfig` como fonte única de autorização; **endpoint novo sem regra explícita = violação** (regra 7 do AGENTS.md deles) | Virar regra do nosso AGENTS.md | ☐ |

### Release engineering — três tiers, um pipeline
| Ideia | Como aplicamos | Status |
|---|---|---|
| **Um pipeline, três tiers de artifact do mesmo commit:** push → imagem imutável `sha-<short7>` + `:edge`; tag anotada `vX.Y.Z` → imagem semver + **GitHub Release com jar + SBOM da imagem exata que shipou** | Commit sempre mapeia pro mesmo jar+imagem; versionamento do Maven fica `1.0-SNAPSHOT` em dev, nome de release vem da git tag | ☐ |
| Deploy **só por tag imutável** — nunca moving; rollback = redeploy da tag anterior | Contrato do nosso deploy blue-green | ☐ |
| **Validação de config fail-fast com relatório agregado**: boot aborta listando TODOS os problemas de uma vez (placeholders não resolvidos, segredo curto, credenciais ausentes fora de dev) | Nosso `ConfigValidator` falha agregado, não um por request | ☐ |
| `docs/ci-vulnerability-gates.md` — a política de gates de CI documentada | Documento curto explicando cada gate e por que existe | ☐ |

### Runtime e testes
| Ideia | Como aplicamos | Status |
|---|---|---|
| **JaCoCo com piso POR MÓDULO** (line 40% + branch 30%, bound ao `verify`) — evita módulo forte mascarar fraco | Nossos pisos por módulo (payments/ledger mais altos, notifications mais baixo) + **medição combinada unidade+IT depois dos ITs** (lição spotpobre) | ☐ |
| Failsafe wired pro `verify` rodar unit + `*IT`; flag `-Dskip.unit.tests=true` |Feedback rápido rodando só ITs | ☐ |
| `FullWorkflowIT` black-box em porta real, sem dependência nova de teste | Nossos E2E | ☐ |
| Webhook atrás de filtro **fail-closed** de assinatura + regra explícita no SecurityConfig (`permitAll` só na rota, validação no filtro) | Nosso intake `/webhooks/psp`: mesmo padrão exato | ✅ (já no design; confirmado as-built) |
| Webhook assinado atrás de proxy: **a app precisa ver a mesma URL pública que o provedor discou** + `forwarded headers` no prod yaml | Gotcha real pro nosso HMAC atrás do NGINX | ☐ |
| Graceful shutdown: `server.shutdown=graceful` + timeout por fase | Complementa nosso shutdown-under-load do CI | ☐ |
| Logs JSON via **profile ECS do Boot 4, sem dependência extra** | Confirma nosso plano de logging built-in | ✅ (já no design) |
| Fontes 100% em inglês (identifiers, comments, logs) | Regra do projeto desde o M0 | ✅ |

### Rate limiting (observação honesta)
Eles têm Redis fixed-window com Lua atômica, **fail-open com WARN** se Redis cair, 429+Retry-After. Pra nós: rate limit por API key é **stretch goal** — e se entrar, o fail-open documentado é a postura honesta a copiar. Sem Redis no core, como decidido.

### O que NÃO copiar
| Item | Motivo |
|---|---|
| **Split de módulos por CAMADA técnica** (api/application/domain/infrastructure como módulos Maven top-level) | Funciona pra eles porque é UM bounded context só (mensageria). Nós temos 3+ contexts — nosso split por módulo de negócio é o ponto central da história de extração. FlowTXT é o contra-exemplo que confirma a regra |
| JWT auth | Nossa decisão: API key Stripe-style |
| Redis obrigatório (cache de auth + rate limit) | Redis é stretch pra nós |

---

## 4. Síntese: o pipeline de CI que vamos montar (fusão dos dois + nosso diferencial)

```
build (gate do PR)
├── mvnw test                          → unidade pura
├── ArchUnit + check-boundaries.sh     → fronteiras duplas (semântica + import/FQN)
├── mvnw spotbugs:check                → 0 bugs
├── OWASP Dependency-Check             → NVD em cache, report-only
├── mvnw test -Dtest='*IT'             → Testcontainers (Postgres + LocalStack + WireMock)
├── mvnw jacoco:check                  → cobertura combinada unidade+IT, com PISO POR MÓDULO (flowtxt)
└── clean package + artifact do jar

image (paralelo, needs: build)
├── docker build (tag = SHA) + registro do digest
├── gate non-root (uid=0 → falha)
├── Trivy: SARIF (Security tab) + gate HIGH/CRITICAL corrigível
└── SBOM CycloneDX + digest como artifacts

security (paralelo — do flowtxt)
├── CodeQL (SAST Java)
└── Dependency Review no PR (dependência nova com licença/vuln → bloqueia)

runtime-smoke (paralelo, gates o pipeline)
├── compose up (postgres + localstack + psp-simulator) → readiness polling
├── jar de produção + migrations Flyway
├── E2E happy path: criar → pagar no simulador → webhook → CONFIRMED → ledger lançou
├── caos: webhook suprimido → reconciler confirma sozinho   ← nosso diferencial
└── graceful shutdown sob carga (drain)                     ← roubo direto do spotpobre

performance (continue-on-error, consultativo)
└── k6 budgets-as-code → artifacts JSON → gate duro após calibração

release (tag anotada vX.Y.Z — flowtxt)
├── todos os gates no commit tagado
└── imagem semver + GitHub Release com jar + SBOM da imagem exata
```

Blue-green em `deploy/` (fora do CI, on-prem): `deploy.sh` (readiness gate → canary 10%/30s → cutover, abort automático), `rollback.sh` (instantâneo), NGINX com `down`/`resolver 127.0.0.11`/checagens passivas — conf runtime copiado, template intocado. Deploy **sempre por tag imutável**; rollback = redeploy da tag anterior.

---

## 5. Decisões cravadas

**Stack (fechada em 28/08/2026):**
| Decisão | Valor |
|---|---|
| JDK | **Java 25** (LTS) |
| Build | **Maven** multi-módulo, split por bounded context |
| Acesso a dados | **JPA/Hibernate no payments** (com entidade de domínio separada) · **JDBC (`JdbcClient`) no ledger** |
| Mensageria | **AWS SDK v2 direto** (adapters nossos, sem Spring Cloud AWS) sobre SNS/SQS FIFO no LocalStack |
| Banco | **PostgreSQL 16** (maduro, EOL 11/2028, suportado pelo Flyway atual; nada no desenho exige 17+) |
| Framework | Spring Boot **4.1.x** (suporte OSS até 07/2027) |
| Governança | **Matriz de aceitação por milestone** (requisito → implementação → teste → evidência + desvios declarados) |
| Pipeline | **Completo com gates de segurança**: SpotBugs, OWASP, Trivy 2-pass, SBOM, non-root, CodeQL, Dependency Review |

**Roubos aprovados em princípio** (detalhar no blueprint): EXHAUSTED+requeue · cultura de docs completa (lessons/release notes/playbook/AGENTS.md) · audit log mínimo · forward-only migrations · error writer canônico único · JaCoCo por módulo · release por tag imutável · config validator agregado · drill de restore do Postgres · k6 (stretch)
