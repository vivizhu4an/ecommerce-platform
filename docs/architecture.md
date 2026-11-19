# Design notes

## Why a conditional UPDATE instead of read-then-write

Reading stock, checking it in Java, then saving the new value lets two requests both read `stock = 1` and both succeed. The conditional `UPDATE ... WHERE stock >= ?` is evaluated by MySQL under the row's lock, so only one wins. The `CHECK (stock >= 0)` constraint is a second line of defense.

Cart items are processed in ascending product id. Every checkout locks rows in the same order, which avoids deadlocks between carts that share products.

## Idempotency keys

Clients send a fresh `Idempotency-Key` per checkout attempt and reuse it on retry (for example after a timeout where the client never saw the response). Two layers handle duplicates:

1. **Lookup first.** A retry that arrives after the first request committed finds the existing order and returns it.
2. **Unique index.** Two requests that arrive at the same moment both miss the lookup. Both reserve stock, but only one insert succeeds. The other fails on `uk_orders_user_idem`, its transaction (including its stock reservation) rolls back, and `OrderService` returns the winning order.

## Transactional outbox

Publishing to RabbitMQ inside the database transaction is unsafe in both directions:

- publish then crash before commit → consumers see an order that does not exist
- commit then crash before publish → the event is lost

Writing the event to `outbox_events` in the same transaction as the order makes them atomic. `OutboxPublisher` relays rows afterwards, so delivery is **at-least-once**: a crash after publishing but before marking the row means it is sent again. Consumers must therefore be idempotent.

### Publisher confirms

`publisher-confirm-type: correlated` makes the broker ack each message once it is safely routed and persisted. `mandatory: true` returns messages that match no queue. A row is marked published only if the confirm is an ack **and** the message was not returned. Otherwise `attempts` is incremented and the row is retried on the next poll.

`FOR UPDATE SKIP LOCKED` lets multiple app instances run the publisher without double-sending the same batch.

## Idempotent consumer

`processed_messages` has the event id as its primary key. The consumer runs `INSERT IGNORE` and its side effect (confirming the order) in one transaction:

- first delivery: insert affects 1 row → do the work → commit both
- redelivery: insert affects 0 rows → skip
- failure: exception rolls back both, so the id is not recorded and a retry can process it

## Retries and dead-lettering

Spring AMQP retries a failing message 3 times with exponential backoff (0.5 s, 1 s). After that the message is rejected with `requeue=false`. The queue's `x-dead-letter-exchange` routes it to `order.created.dlq`, where it can be inspected and replayed instead of blocking the queue or being silently dropped.

## Caching

`GET /api/products/{id}` is cached in Redis with a 10-minute TTL.

- **Evict after commit, not before.** If the cache is evicted before the transaction commits, a concurrent read can miss the cache, load the old row and put it back. `ProductCacheEvictor` registers a `TransactionSynchronization.afterCommit` hook instead.
- **Never trust cached stock for writes.** The cached `stock` is display-only. Checkout reserves stock and reads prices from MySQL inside its transaction.
- The TTL is a safety net in case an eviction is missed (for example Redis is briefly unavailable).

## Observability

Actuator plus Micrometer expose:

- request latency histograms per endpoint and status (`http.server.requests`), used to find slow or failing endpoints
- business counters: checkout outcomes, consumer duplicates
- a gauge of pending outbox rows; if it keeps growing, the broker is unreachable or rejecting messages
