package io.dargent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Management port isolation IT (E11 S0): verifies actuator is served ONLY on the isolated
 * management port, never on the main business port. Prod-like profile boots with
 * {@code management.server.port} from {@code DARGENT_MANAGEMENT_PORT} (default 9090).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = DargentApiApplication.class,
    properties = {
        "spring.profiles.active=prod",
        "management.server.port=9090",
        "DARGENT_DB_PASSWORD=prod-test-password-that-is-at-least-32-chars-long",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "PSP_BASE_URL=http://psp-simulator:8090",
        "PSP_WEBHOOK_SECRET=prod-test-webhook-secret-that-is-long-enough",
        "dargent.psp.webhook-secret=prod-test-webhook-secret-that-is-long-enough",
        "dargent.relay.enabled=false"
    }
)
@Testcontainers
class ManagementPortIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int mainPort;

    @Autowired
    Environment env;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void mainPort_actuatorEndpoints_areDenied() throws Exception {
        // Given: main port serves business endpoints, actuator must be denied by denyAll
        String baseUrl = "http://localhost:" + mainPort;

        // When: requesting actuator/health on main port
        var healthReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/actuator/health"))
                .GET().build();
        var healthResp = http.send(healthReq, HttpResponse.BodyHandlers.ofString());

        // Then: actuator on main port is denied (exact status from denyAll + auth filter chain)
        // denyAll triggers AuthenticationEntryPoint -> 401 for anonymous
        assertThat(healthResp.statusCode())
                .as("actuator/health on main port must be denied by denyAll (401/403/404)")
                .isIn(401, 403, 404);

        // When: requesting actuator/prometheus on main port
        var promReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/actuator/prometheus"))
                .GET().build();
        var promResp = http.send(promReq, HttpResponse.BodyHandlers.ofString());

        // Then: prometheus on main port is also denied
        assertThat(promResp.statusCode())
                .as("actuator/prometheus on main port must be denied")
                .isIn(401, 403, 404);

        // When: requesting actuator/info on main port
        var infoReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/actuator/info"))
                .GET().build();
        var infoResp = http.send(infoReq, HttpResponse.BodyHandlers.ofString());

        // Then: info on main port is also denied
        assertThat(infoResp.statusCode())
                .as("actuator/info on main port must be denied")
                .isIn(401, 403, 404);
    }

    @Test
    void managementPort_servesHealthAndPrometheus() throws Exception {
        // Given: management port fixed at 9090 for test
        int managementPort = 9090;
        String mgmtBase = "http://localhost:" + managementPort;

        // When: requesting health on management port
        var healthReq = HttpRequest.newBuilder()
                .uri(URI.create(mgmtBase + "/actuator/health"))
                .GET().build();
        var healthResp = http.send(healthReq, HttpResponse.BodyHandlers.ofString());

        // Then: health is 200 UP with no details (show-details: never)
        assertThat(healthResp.statusCode()).isEqualTo(200);
        assertThat(healthResp.body()).contains("\"status\":\"UP\"");
        assertThat(healthResp.body()).doesNotContain("details");
        assertThat(healthResp.body()).doesNotContain("db");

        // When: requesting prometheus on management port
        var promReq = HttpRequest.newBuilder()
                .uri(URI.create(mgmtBase + "/actuator/prometheus"))
                .GET().build();
        var promResp = http.send(promReq, HttpResponse.BodyHandlers.ofString());

        // Then: prometheus returns exposition text (registry live)
        assertThat(promResp.statusCode()).isEqualTo(200);
        assertThat(promResp.body()).contains("jvm_memory_used_bytes");
        assertThat(promResp.body()).contains("jvm_threads_live_threads");
    }

    @Test
    void managementPort_infoEndpoint_servesBuildInfo() throws Exception {
        int managementPort = 9090;
        String mgmtBase = "http://localhost:" + managementPort;

        var infoReq = HttpRequest.newBuilder()
                .uri(URI.create(mgmtBase + "/actuator/info"))
                .GET().build();
        var infoResp = http.send(infoReq, HttpResponse.BodyHandlers.ofString());

        assertThat(infoResp.statusCode()).isEqualTo(200);
        // info endpoint returns build info if present, at minimum empty JSON
        assertThat(infoResp.body()).isNotBlank();
    }
}