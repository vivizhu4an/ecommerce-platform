package com.keranzhu.shop;

import com.keranzhu.shop.catalog.Product;
import com.keranzhu.shop.catalog.ProductView;
import com.keranzhu.shop.order.OrderRepository;
import com.keranzhu.shop.order.OrderView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutIntegrationTest extends IntegrationTestBase {

    @Autowired
    OrderRepository orders;

    @Test
    void retryWithSameIdempotencyKeyReturnsSameOrderAndReservesStockOnce() {
        Product p = newProduct(10);
        String token = newUserToken();
        putInCart(token, p.getId(), 3);
        String key = UUID.randomUUID().toString();

        ResponseEntity<OrderView> first = checkout(token, key, OrderView.class);
        ResponseEntity<OrderView> retry = checkout(token, key, OrderView.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(stockOf(p.getId())).isEqualTo(7);
    }

    @Test
    void concurrentDuplicateCheckoutsCreateExactlyOneOrder() throws Exception {
        Product p = newProduct(10);
        String token = newUserToken();
        putInCart(token, p.getId(), 2);
        String key = UUID.randomUUID().toString();

        List<ResponseEntity<OrderView>> results = runConcurrently(8, i -> checkout(token, key, OrderView.class));

        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertThat(results.stream().map(r -> r.getBody().id()).distinct()).hasSize(1);
        assertThat(stockOf(p.getId())).isEqualTo(8);
    }

    @Test
    void stockContentionNeverOversells() throws Exception {
        int stock = 5;
        int buyers = 12;
        Product p = newProduct(stock);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String t = newUserToken();
            putInCart(t, p.getId(), 1);
            tokens.add(t);
        }

        List<ResponseEntity<String>> results = runConcurrently(buyers,
                i -> checkout(tokens.get(i), UUID.randomUUID().toString(), String.class));

        long ok = results.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        long conflict = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
        assertThat(ok).isEqualTo(stock);
        assertThat(conflict).isEqualTo(buyers - stock);
        assertThat(stockOf(p.getId())).isZero();
    }

    @Test
    void failedCheckoutRollsBackEarlierReservations() {
        Product plenty = newProduct(10);
        Product scarce = newProduct(1);
        String token = newUserToken();
        putInCart(token, plenty.getId(), 2);
        putInCart(token, scarce.getId(), 2); // more than available

        ResponseEntity<String> res = checkout(token, UUID.randomUUID().toString(), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stockOf(plenty.getId())).isEqualTo(10);
        assertThat(stockOf(scarce.getId())).isEqualTo(1);
    }

    @Test
    void cachedProductIsRefreshedAfterCheckoutCommits() {
        Product p = newProduct(10);
        String token = newUserToken();

        // First read populates Redis.
        assertThat(http.getForObject("/api/products/" + p.getId(), ProductView.class).stock()).isEqualTo(10);

        putInCart(token, p.getId(), 4);
        assertThat(checkout(token, UUID.randomUUID().toString(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(http.getForObject("/api/products/" + p.getId(), ProductView.class).stock()).isEqualTo(6);
    }

    @Test
    void checkoutRequiresAuthentication() {
        ResponseEntity<String> res = http.postForEntity("/api/orders/checkout", null, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static <T> List<T> runConcurrently(int n, java.util.function.IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.apply(idx);
                }));
            }
            start.countDown(); // release all threads at once
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
