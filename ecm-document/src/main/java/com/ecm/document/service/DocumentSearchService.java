package com.ecm.document.service;

import com.ecm.document.dto.SearchRequest;
import com.ecm.document.dto.SearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.search.SearchAction;
import org.opensearch.action.search.SearchRequestBuilder;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.unit.Fuzziness;
import org.opensearch.index.query.*;
import org.opensearch.search.SearchHit;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.bucket.terms.Terms;
import org.opensearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.opensearch.search.suggest.SuggestBuilder;
import org.opensearch.search.suggest.SuggestBuilders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * DocumentSearchService — queries ecm-documents OpenSearch index.
 *
 * Query strategy:
 *   1. Every query is scoped to tenantId = "default" (multi-tenant ready)
 *   2. Status DELETED is always excluded
 *   3. ECM_READONLY cannot see extractedFields (PII protection)
 *   4. Free text uses multi_match across documentName (^3 boost), extractedText (^1),
 *      and extractedFields.* (^2)
 *   5. Facets returned for: category, segment, status, mimeType
 *   6. Highlights on extractedText (150 char fragments, max 3)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentSearchService {

    private final RestHighLevelClient openSearch;
    private final ObjectMapper objectMapper;

    @Value("${ecm.opensearch.index-name:ecm-documents}")
    private String indexName;

    public SearchResponse search(SearchRequest req) {
        SearchSourceBuilder source = new SearchSourceBuilder();

        // ── Root query: bool with must + filter ───────────────────────
        BoolQueryBuilder query = QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery("tenantId", "default"))
                .mustNot(QueryBuilders.termQuery("status", "DELETED"));

        // ── Full-text query ───────────────────────────────────────────
        if (req.q() != null && !req.q().isBlank()) {
            query.must(QueryBuilders.multiMatchQuery(req.q())
                    .field("documentName", 3.0f)
                    .field("extractedText",  1.0f)
                    .field("extractedFields.*", 2.0f)
                    .field("tags", 1.5f)
                    .type(MultiMatchQueryBuilder.Type.BEST_FIELDS)
                    .fuzziness(Fuzziness.AUTO)
                    .minimumShouldMatch("75%"));
        } else {
            query.must(QueryBuilders.matchAllQuery());
        }

        // ── Filters ───────────────────────────────────────────────────
        if (req.categoryIds() != null && !req.categoryIds().isEmpty()) {
            query.filter(QueryBuilders.termsQuery("categoryId",
                    req.categoryIds().stream().map(Object::toString).toArray()));
        }
        if (req.segmentIds() != null && !req.segmentIds().isEmpty()) {
            query.filter(QueryBuilders.termsQuery("segmentId",
                    req.segmentIds().stream().map(Object::toString).toArray()));
        }
        if (req.statuses() != null && !req.statuses().isEmpty()) {
            query.filter(QueryBuilders.termsQuery("status", req.statuses().toArray()));
        }
        if (req.mimeTypes() != null && !req.mimeTypes().isEmpty()) {
            query.filter(QueryBuilders.termsQuery("mimeType", req.mimeTypes().toArray()));
        }
        if (req.from() != null || req.to() != null) {
            var range = QueryBuilders.rangeQuery("uploadedAt");
            if (req.from() != null) range.gte(req.from());
            if (req.to()   != null) range.lte(req.to());
            query.filter(range);
        }

        source.query(query);

        // ── Pagination ────────────────────────────────────────────────
        source.from(req.page() * req.size()).size(req.size());

        // ── Aggregations (facets) ─────────────────────────────────────
        source.aggregation(AggregationBuilders.terms("by_category").field("categoryCode").size(50));
        source.aggregation(AggregationBuilders.terms("by_status").field("status").size(20));
        source.aggregation(AggregationBuilders.terms("by_mime").field("mimeType").size(20));
        source.aggregation(AggregationBuilders.terms("by_segment").field("segmentId").size(20));

        // ── Highlights ────────────────────────────────────────────────
        if (req.highlight()) {
            source.highlighter(new HighlightBuilder()
                    .field(new HighlightBuilder.Field("extractedText")
                            .numOfFragments(3).fragmentSize(150))
                    .field(new HighlightBuilder.Field("documentName").numOfFragments(1))
                    .preTags("<mark>").postTags("</mark>"));
        }

        // ── Execute ───────────────────────────────────────────────────
        try {
            var osRequest = new org.opensearch.action.search.SearchRequest(indexName);
            osRequest.source(source);
            var osResponse = openSearch.search(osRequest, RequestOptions.DEFAULT);

            boolean isReadonly = isReadonlyRole();

            // ── Map hits ──────────────────────────────────────────────
            List<SearchResponse.SearchHit> hits = new ArrayList<>();
            for (SearchHit hit : osResponse.getHits().getHits()) {
                Map<String, Object> src = hit.getSourceAsMap();

                // PII guard: strip extractedFields for ECM_READONLY
                Map<String, Object> fields = isReadonly ? Map.of()
                        : castMap(src.get("extractedFields"));

                String hlName    = req.highlight() && hit.getHighlightFields().containsKey("documentName")
                        ? hit.getHighlightFields().get("documentName").fragments()[0].string() : null;
                String hlSnippet = req.highlight() && hit.getHighlightFields().containsKey("extractedText")
                        ? Arrays.stream(hit.getHighlightFields().get("extractedText").fragments())
                        .map(t -> t.string()).reduce("", (a, b) -> a + " … " + b).strip()
                        : null;

                String[] tags = src.get("tags") instanceof List<?>
                        ? ((List<?>) src.get("tags")).stream().map(Object::toString).toArray(String[]::new)
                        : new String[0];

                hits.add(new SearchResponse.SearchHit(
                        str(src, "documentId"), str(src, "documentName"),
                        str(src, "status"), str(src, "mimeType"),
                        intVal(src, "categoryId"), str(src, "categoryCode"),
                        intVal(src, "segmentId"), intVal(src, "productLineId"),
                        str(src, "uploadedBy"), str(src, "uploadedAt"),
                        hlName, hlSnippet, fields, tags));
            }

            // ── Map facets ────────────────────────────────────────────
            Map<String, List<SearchResponse.FacetBucket>> facets = new LinkedHashMap<>();
            mapFacet(osResponse, "by_category", facets);
            mapFacet(osResponse, "by_status",   facets);
            mapFacet(osResponse, "by_mime",     facets);
            mapFacet(osResponse, "by_segment",  facets);

            long total = osResponse.getHits().getTotalHits().value;
            int  pages = (int) Math.ceil((double) total / req.size());

            log.debug("Search '{}': {} hits, {}ms", req.q(), total,
                    osResponse.getTook().millis());

            return new SearchResponse(hits, total, req.page(), req.size(), pages, facets, List.of());

        } catch (Exception e) {
            log.error("Search failed: {}", e.getMessage(), e);
            return new SearchResponse(List.of(), 0, req.page(), req.size(), 0, Map.of(), List.of());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private boolean isReadonlyRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ECM_READONLY"));
    }

    private void mapFacet(org.opensearch.action.search.SearchResponse res,
                          String aggName,
                          Map<String, List<SearchResponse.FacetBucket>> target) {
        var agg = res.getAggregations().get(aggName);
        if (agg instanceof Terms terms) {
            target.put(aggName.replace("by_", ""),
                    terms.getBuckets().stream()
                            .map(b -> new SearchResponse.FacetBucket(b.getKeyAsString(), b.getDocCount()))
                            .toList());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key); return v != null ? v.toString() : null;
    }

    private Integer intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (Exception ignored) {} }
        return null;
    }
}