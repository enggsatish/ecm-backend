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
import java.util.List;

/**
 * Reactive HTTP client for ecm-identity's internal enrichment endpoint.
 *
 * Timeout: 4s total (2s connect + 2s read).
 * EcmRoleEnrichmentFilter handles timeout/connection errors via onErrorResume.
 *
 * Sprint G-fix: enrich() now accepts oktaGroups and forwards them to identity
 * so fresh-DB bootstrap works without manual SQL.
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
     * @param sub        JWT sub claim (entra_object_id)
     * @param email      JWT email claim
     * @param oktaGroups JWT groups claim — forwarded for first-run bootstrap detection
     */
    public Mono<EnrichmentResponseDto> enrich(String sub, String email, List<String> oktaGroups) {
        EnrichmentRequestDto request = new EnrichmentRequestDto(sub, email, oktaGroups);

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