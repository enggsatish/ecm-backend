package com.ecm.gateway.client;

import com.ecm.gateway.dto.EnrichmentRequestDto;
import com.ecm.gateway.dto.EnrichmentResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reactive HTTP client for ecm-identity's internal enrichment endpoint.
 *
 * Timeout: 2s connect + 2s read (total ~4s max wait).
 * The EcmRoleEnrichmentFilter handles WebClientRequestException (timeout/connection
 * refused) via onErrorResume — a timed-out call triggers the degraded-mode path.
 */
@Slf4j
@Component
public class IdentityEnrichmentClient {

    private final WebClient webClient;

    public IdentityEnrichmentClient(
            @Value("${ecm.identity.url:http://localhost:8081}") String identityUrl,
            @Value("${ecm.enrichment.timeout-ms:2000}") long timeoutMs) {

        this.webClient = WebClient.builder()
                .baseUrl(identityUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("IdentityEnrichmentClient configured → {} (timeout {}ms)", identityUrl, timeoutMs);
    }

    /**
     * POST /internal/auth/enrich
     *
     * Returns Mono<EnrichmentResponseDto> that the filter flatMaps into.
     * On network failure, the Mono errors — caller handles via onErrorResume.
     */
    public Mono<EnrichmentResponseDto> enrich(String sub, String email) {
        EnrichmentRequestDto request = new EnrichmentRequestDto(sub, email);

        return webClient.post()
                .uri("/internal/auth/enrich")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EnrichmentResponseDto.class)
                .timeout(Duration.ofSeconds(4))
                .doOnError(e -> log.warn("Identity enrichment call failed for sub={}: {}", sub, e.getMessage()));
    }
}
