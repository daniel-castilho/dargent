package io.dargent.payments.adapter.out.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Debug test with proper WireMock configuration
 */
class HttpClientDebugTest2 {

    private WireMockServer wireMock;
    private HttpClient httpClient;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        wireMock = new WireMockServer(com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        int port = wireMock.port();
        System.out.println("WireMock started on port: " + port);
        
        // Configure WireMock static client to use the correct admin port
        configureFor("localhost", wireMock.port());
        
        httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
        System.out.println("Created HttpClient");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void check_system_properties_and_simple_get() throws Exception {
        String url = "http://localhost:" + wireMock.port() + "/test";
        System.out.println("Testing URL: " + url);
        
        stubFor(post(urlEqualTo("/test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"result\": \"ok\"}")));

        var request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();
        
        System.out.println("Request URI: " + request.uri());
        
        var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        System.out.println("Response status: " + response.statusCode());
        System.out.println("Response body: " + response.body());
    }
}