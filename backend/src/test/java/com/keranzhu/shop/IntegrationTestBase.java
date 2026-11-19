package com.keranzhu.shop;

import com.keranzhu.shop.catalog.Product;
import com.keranzhu.shop.catalog.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Boots the whole app against real MySQL, Redis and RabbitMQ containers.
 * Containers are started once per JVM and shared by every test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    static {
        MYSQL.start();
        RABBIT.start();
        REDIS.start();
    }

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected ProductRepository products;

    public record Token(String accessToken, String tokenType, String role) {
    }

    /** Registers a fresh user and returns its bearer token. */
    protected String newUserToken() {
        var body = Map.of("email", "user-" + UUID.randomUUID() + "@test.local", "password", "password123");
        ResponseEntity<Token> res = http.postForEntity("/api/auth/register", body, Token.class);
        if (res.getStatusCode() != HttpStatus.CREATED || res.getBody() == null) {
            throw new IllegalStateException("register failed: " + res.getStatusCode());
        }
        return res.getBody().accessToken();
    }

    protected Product newProduct(int stock) {
        String sku = "TEST-" + UUID.randomUUID().toString().substring(0, 8);
        return products.save(new Product(sku, "Test " + sku, null, new BigDecimal("10.00"), stock));
    }

    protected HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected void putInCart(String token, long productId, int qty) {
        var req = new HttpEntity<>(Map.of("productId", productId, "quantity", qty), auth(token));
        ResponseEntity<String> res = http.exchange("/api/cart/items", HttpMethod.PUT, req, String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("add to cart failed: " + res.getStatusCode() + " " + res.getBody());
        }
    }

    protected <T> ResponseEntity<T> checkout(String token, String idempotencyKey, Class<T> type) {
        HttpHeaders h = auth(token);
        h.set("Idempotency-Key", idempotencyKey);
        return http.exchange("/api/orders/checkout", HttpMethod.POST, new HttpEntity<>(h), type);
    }

    protected int stockOf(long productId) {
        return products.findById(productId).orElseThrow().getStock();
    }
}
