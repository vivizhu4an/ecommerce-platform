# E-Commerce Order & Inventory Platform

A full-stack shop built to practice the hard parts of order processing: **no overselling, no duplicate orders, and no lost events**, even under concurrent requests, client retries and message redelivery.

**Stack:** Java 21 · Spring Boot 3 · Spring Security (JWT) · MySQL 8 · Redis · RabbitMQ · React (Vite) · Docker Compose · JUnit 5 · Testcontainers · GitHub Actions

## Features

| Area | What it does |
|---|---|
| Catalog | Public product browsing; admin-only create/update (`ROLE_ADMIN`) |
| Auth | Register/login, stateless JWT (HS256), role-based authorization |
| Cart | Per-user cart with quantity updates |
| Checkout | Transactional stock reservation, idempotent via `Idempotency-Key` header |
| Events | `order.created` published through a transactional outbox to RabbitMQ |
| Consumer | Idempotent consumer, bounded retries, dead-letter queue |
| Caching | Product reads cached in Redis with TTL; evicted after DB commit |
| Observability | Actuator health, Micrometer metrics, Prometheus endpoint |

## Architecture

```
React ──HTTP──> Spring Boot API ──JPA──> MySQL  (products, orders, outbox_events, processed_messages)
                    │   │
                    │   └──cache──> Redis (product reads)
                    │
            OutboxPublisher ──publish + confirm──> RabbitMQ: shop.events ──> order.created.q ──> OrderCreatedConsumer
                                                                                  │ (after 3 failed attempts)
                                                                                  └──> shop.events.dlx ──> order.created.dlq
```

More detail on each design decision is in [`docs/architecture.md`](docs/architecture.md).

### Checkout in one transaction

1. If an order already exists for `(user, Idempotency-Key)`, return it.
2. Reserve stock per item with a conditional update:
   `UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?`
   If any item affects 0 rows, the whole transaction rolls back.
3. Insert the order. A unique index on `(user_id, idempotency_key)` makes a concurrent duplicate fail and roll back; the API then returns the winner's order.
4. Write an `OrderCreated` row to `outbox_events` in the **same** transaction and clear the cart.
5. After commit, evict the affected products from Redis.

Stock checks always hit MySQL. The cache is only used for reads that tolerate slight staleness.

### Reliable messaging

- **Outbox relay**: a scheduled publisher claims rows with `FOR UPDATE SKIP LOCKED`, publishes with **publisher confirms** and `mandatory=true`, and marks a row published only after a broker ack with no return.
- **Idempotent consumer**: `INSERT IGNORE INTO processed_messages(event_id)` runs in the same transaction as the side effect, so redeliveries are skipped.
- **Retries and DLQ**: 3 attempts with exponential backoff, then reject without requeue → `order.created.dlq`.

## Running locally

Requirements: Docker. (For running outside Docker: JDK 21, Maven 3.9, Node 20.)

```bash
docker compose up --build
```

- Shop UI: http://localhost:3000
- API docs (Swagger UI): http://localhost:8080/swagger-ui.html
- RabbitMQ management: http://localhost:15672 (guest / guest)
- Metrics: http://localhost:8080/actuator/prometheus

A default admin is created on first start (`admin@shop.local` / `admin12345`; override with `ADMIN_EMAIL` / `ADMIN_PASSWORD`). Six sample products are seeded by Flyway.

### Dev mode

```bash
docker compose up mysql redis rabbitmq     # infrastructure only
cd backend && mvn spring-boot:run           # API on :8080
cd frontend && npm install && npm run dev   # UI on :5173, proxies /api to :8080
```

## API

| Method | Path | Auth |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | public |
| GET | `/api/products`, `/api/products/{id}` | public |
| POST / PUT | `/api/admin/products`, `/api/admin/products/{id}` | admin |
| GET / PUT / DELETE | `/api/cart`, `/api/cart/items`, `/api/cart` | user |
| POST | `/api/orders/checkout` (header `Idempotency-Key`) | user |
| GET | `/api/orders`, `/api/orders/{id}` | user |

Example:

```bash
TOKEN=$(curl -s localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"password123"}' | jq -r .accessToken)

curl -X PUT localhost:8080/api/cart/items -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"productId":1,"quantity":2}'

KEY=$(uuidgen)
curl -X POST localhost:8080/api/orders/checkout -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY"
curl -X POST localhost:8080/api/orders/checkout -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY"  # same order back
```

## Tests

```bash
cd backend && mvn verify
```

Integration tests start real MySQL, Redis and RabbitMQ with Testcontainers (Docker required) and cover:

- retrying checkout with the same key returns the same order and reserves stock once
- 8 concurrent requests with one key create exactly one order
- 12 buyers racing for 5 units: exactly 5 succeed, stock ends at 0, never negative
- a checkout that fails on its second item rolls back the first item's reservation
- a cached product shows updated stock right after checkout commits
- outbox events reach RabbitMQ and the consumer confirms the order
- the same message delivered 3 times is processed once
- a malformed message is retried, then dead-lettered

CI runs the same suite on every push (`.github/workflows/ci.yml`).

## Metrics worth looking at

- `http_server_requests_seconds` (latency histogram per endpoint and status)
- `shop_checkout_total{outcome="success|duplicate|out_of_stock"}`
- `shop_outbox_pending` (events waiting to be confirmed by the broker)
- `shop_consumer_duplicates_total`

## Possible next steps

- Payment step with order expiry and stock release for unpaid orders
- Refresh tokens and logout
- Paginated order history
- Cache product list pages, not just single products
