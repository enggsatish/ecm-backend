package com.ecm.ocr.classification;

import com.ecm.common.client.AdminServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Matches document content to existing customers using extracted fields.
 * <p>
 * Extracts identifiers (account number, name, DOB) from OCR text via regex,
 * then searches ecm-admin customer API for matches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerMatcherService {

    private final AdminServiceClient adminServiceClient;

    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile(
            "(?:Account\\s*#|Acct[:\\s]|Member\\s*No[:\\s.]|Account\\s*Number[:\\s])\\s*(\\d{4,15})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(?:Name|Customer|Borrower|Applicant|Account\\s*Holder)[:\\s]+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DOB_PATTERN = Pattern.compile(
            "(?:DOB|Date\\s*of\\s*Birth)[:\\s]+([\\d]{1,2}[/\\-][\\d]{1,2}[/\\-][\\d]{2,4}|[A-Z][a-z]+\\s+\\d{1,2},?\\s+\\d{4})",
            Pattern.CASE_INSENSITIVE);

    /** Titles and suffixes to strip during name normalization. */
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "\\b(?:Mr|Mrs|Ms|Miss|Dr|Jr|Sr|III|II)\\.?\\b",
            Pattern.CASE_INSENSITIVE);

    /** Detects "LASTNAME, FIRSTNAME" format. */
    private static final Pattern LAST_FIRST_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z'-]+)\\s*,\\s*(.+)$");

    public CustomerMatchResult match(String ocrText) {
        return match(ocrText, Map.of());
    }

    public CustomerMatchResult match(String ocrText, Map<String, String> extractedFields) {
        String text = ocrText != null ? ocrText : "";
        Map<String, String> fields = extractedFields != null ? extractedFields : Map.of();

        if (text.isBlank() && fields.isEmpty()) {
            log.warn("No OCR text or fields available for customer matching");
            return new CustomerMatchResult(null, null, BigDecimal.ZERO, null, List.of());
        }

        // Step 1: Extract identifiers — prefer structured fields, fall back to regex
        String accountNumber = firstNonBlank(
                fields.get("account_number"), fields.get("member_number"),
                extractFirst(ACCOUNT_NUMBER_PATTERN, text));
        // Name resolution: prefer full_name, fall back to first+last, then regex
        // full_name may already contain all given names + surname (e.g., "Erin Amanda Jane Anderson")
        String rawName = firstNonBlank(
                fields.get("full_name"), fields.get("name"),
                fields.get("first_name") != null && fields.get("last_name") != null
                        ? fields.get("first_name") + " " + fields.get("last_name") : null,
                // Also try last_name alone (partial match is better than no match)
                fields.get("last_name"),
                extractFirst(NAME_PATTERN, text));
        String name = normalizeName(rawName);
        String dob = firstNonBlank(
                fields.get("date_of_birth"), fields.get("dob"),
                extractFirst(DOB_PATTERN, text));

        log.debug("Extracted identifiers — account: {}, name: {} (raw: {}), dob: {}",
                accountNumber, name, rawName, dob);

        // Step 2: Try account number match first (highest confidence)
        if (accountNumber != null) {
            List<Map<String, Object>> results = adminServiceClient.searchCustomers(accountNumber);
            if (!results.isEmpty()) {
                UUID customerId = extractCustomerId(results.get(0));
                String extId = extractExternalId(results.get(0));
                if (customerId != null) {
                    log.info("Account number match found: {} -> customer {} (ext={})", accountNumber, customerId, extId);
                    List<UUID> candidateIds = results.stream()
                            .map(this::extractCustomerId).filter(Objects::nonNull).toList();
                    return new CustomerMatchResult(customerId, extId, new BigDecimal("95.00"),
                            "account:" + accountNumber, candidateIds);
                }
            }
        }

        // Step 3: Try name match (medium confidence)
        if (name != null) {
            List<Map<String, Object>> results = adminServiceClient.searchCustomers(name.trim());
            if (!results.isEmpty()) {
                List<UUID> candidateIds = results.stream()
                        .map(this::extractCustomerId).filter(Objects::nonNull).toList();
                UUID bestMatch = candidateIds.isEmpty() ? null : candidateIds.get(0);
                String extId = extractExternalId(results.get(0));

                BigDecimal confidence;
                if (results.size() == 1) {
                    log.info("Single name match found: {} -> customer {} (ext={})", name, bestMatch, extId);
                    confidence = new BigDecimal("80.00");
                } else {
                    log.info("Multiple name matches ({}) found for: {}", results.size(), name);
                    confidence = new BigDecimal("50.00");
                }

                // Multi-field boost: if DOB also matches, boost confidence by 10%
                confidence = applyDobBoost(confidence, dob, results.get(0));

                return new CustomerMatchResult(bestMatch, extId, confidence,
                        "name:" + name.trim(), candidateIds);
            }
        }

        // Step 4: Fuzzy name match — broader search with last name only
        if (name != null) {
            String lastName = extractLastName(name);
            if (lastName != null && !lastName.equals(name)) {
                List<Map<String, Object>> results = adminServiceClient.searchCustomers(lastName);
                if (!results.isEmpty()) {
                    // Compare each result using Jaccard similarity on word tokens
                    Map<String, Object> bestCandidate = null;
                    double bestSim = 0.0;
                    int matchCount = 0;

                    for (Map<String, Object> candidate : results) {
                        String displayName = extractDisplayName(candidate);
                        if (displayName == null) continue;

                        double sim = similarity(name, normalizeName(displayName));
                        if (sim > bestSim) {
                            bestSim = sim;
                            bestCandidate = candidate;
                        }
                        if (sim > 0.7) matchCount++;
                    }

                    if (bestSim > 0.7 && bestCandidate != null) {
                        List<UUID> candidateIds = results.stream()
                                .map(this::extractCustomerId).filter(Objects::nonNull).toList();
                        UUID bestMatch = extractCustomerId(bestCandidate);
                        String extId = extractExternalId(bestCandidate);

                        BigDecimal confidence;
                        if (matchCount == 1) {
                            log.info("Fuzzy name match (sim={}): {} -> customer {} (ext={})",
                                    bestSim, name, bestMatch, extId);
                            confidence = new BigDecimal("70.00");
                        } else {
                            log.info("Multiple fuzzy matches ({}, bestSim={}) for: {}",
                                    matchCount, bestSim, name);
                            confidence = new BigDecimal("45.00");
                        }

                        // Multi-field boost: DOB match
                        confidence = applyDobBoost(confidence, dob, bestCandidate);

                        return new CustomerMatchResult(bestMatch, extId, confidence,
                                "fuzzy_name:" + name.trim(), candidateIds);
                    }
                }
            }
        }

        log.debug("No customer match found from OCR text identifiers");
        return new CustomerMatchResult(null, null, BigDecimal.ZERO, null, List.of());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private String extractFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private UUID extractCustomerId(Map<String, Object> customerData) {
        // Get the party UUID (parties.id) — used for case linking
        Object id = customerData.get("id");
        if (id == null) return null;
        try {
            return id instanceof UUID ? (UUID) id : UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse customer UUID: {}", id);
            return null;
        }
    }

    /**
     * Extract the external_id (e.g., CUST-RET-003) which is what documents
     * store in party_external_id for linking to customer portfolios.
     */
    static String extractExternalId(Map<String, Object> customerData) {
        // PartyDto uses customerRef; some legacy responses use externalId/external_id
        Object extId = customerData.get("customerRef");
        if (extId == null) extId = customerData.get("externalId");
        if (extId == null) extId = customerData.get("external_id");
        return extId != null ? extId.toString() : null;
    }

    // ── Name normalization ──────────────────────────────────────────────────

    /**
     * Normalize a name for matching:
     * <ul>
     *   <li>Strip titles (Mr., Mrs., Ms., Dr., Jr., Sr., III, II)</li>
     *   <li>Handle "LASTNAME, FIRSTNAME" format (flip to "FIRSTNAME LASTNAME")</li>
     *   <li>Normalize whitespace (multiple spaces to single)</li>
     *   <li>Trim punctuation at start/end</li>
     * </ul>
     */
    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) return null;

        String result = name.trim();

        // Strip titles and suffixes
        result = TITLE_PATTERN.matcher(result).replaceAll("").trim();

        // Handle "LASTNAME, FIRSTNAME" format → "FIRSTNAME LASTNAME"
        Matcher lfMatcher = LAST_FIRST_PATTERN.matcher(result);
        if (lfMatcher.matches()) {
            result = lfMatcher.group(2).trim() + " " + lfMatcher.group(1).trim();
        }

        // Normalize whitespace
        result = result.replaceAll("\\s+", " ").trim();

        // Trim leading/trailing punctuation
        result = result.replaceAll("^[^A-Za-z]+", "").replaceAll("[^A-Za-z]+$", "").trim();

        return result.isBlank() ? null : result;
    }

    // ── Fuzzy matching ──────────────────────────────────────────────────────

    /**
     * Jaccard similarity on word tokens: |intersection| / |union|.
     *
     * @return 0.0 to 1.0
     */
    static double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;

        Set<String> wordsA = tokenize(a);
        Set<String> wordsB = tokenize(b);

        if (wordsA.isEmpty() && wordsB.isEmpty()) return 1.0;
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);

        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);

        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("\\s+"))
                .filter(w -> !w.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * Extract the last word as last name from a full name string.
     */
    private static String extractLastName(String name) {
        if (name == null || name.isBlank()) return null;
        String[] parts = name.trim().split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : null;
    }

    /**
     * Extract display_name from customer search result.
     */
    private static String extractDisplayName(Map<String, Object> customerData) {
        Object name = customerData.get("displayName");
        if (name == null) name = customerData.get("display_name");
        if (name == null) name = customerData.get("name");
        return name != null && !name.toString().isBlank() ? name.toString() : null;
    }

    // ── Multi-field boost ───────────────────────────────────────────────────

    /**
     * Boost confidence by 10% if DOB matches the customer record.
     * Only applies if both the extracted DOB and the customer DOB are available.
     */
    private static BigDecimal applyDobBoost(BigDecimal confidence, String extractedDob,
                                            Map<String, Object> customerData) {
        if (extractedDob == null || extractedDob.isBlank()) return confidence;

        Object customerDob = customerData.get("dateOfBirth");
        if (customerDob == null) customerDob = customerData.get("date_of_birth");
        if (customerDob == null) customerDob = customerData.get("dob");
        if (customerDob == null) return confidence;

        // Normalize both DOBs by stripping non-alphanumeric chars for comparison
        String normalizedExtracted = extractedDob.replaceAll("[^0-9]", "");
        String normalizedCustomer = customerDob.toString().replaceAll("[^0-9]", "");

        if (!normalizedExtracted.isEmpty() && normalizedExtracted.equals(normalizedCustomer)) {
            BigDecimal boosted = confidence.add(new BigDecimal("10.00"));
            BigDecimal capped = boosted.min(new BigDecimal("99.00"));
            log.info("DOB match boost: {}% -> {}%", confidence, capped);
            return capped;
        }
        return confidence;
    }
}
