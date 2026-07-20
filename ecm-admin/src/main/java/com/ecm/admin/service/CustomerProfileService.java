package com.ecm.admin.service;

import com.ecm.admin.dto.CustomerCrmProfileDto;
import com.ecm.admin.entity.*;
import com.ecm.admin.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates a customer's canonical CRM profile — resolves Tier A (flat
 * profile attributes: MANUAL from ecm_admin.customer_profile_values,
 * CRM_MAPPED live from Salesforce) and Tier B (relationship lists: memberships,
 * accounts, live from Salesforce). See design note "CRM-Aware Form Fill &
 * Customer 360" (2026-07-17) — deliberately no caching/sync, live lookup only.
 */
@Slf4j
@Service
@Transactional
public class CustomerProfileService {

    private final PartyRepository partyRepository;
    private final CustomerProfileAttributeRepository attributeRepo;
    private final CustomerProfileAttributeMappingRepository mappingRepo;
    private final CustomerProfileValueRepository valueRepo;
    private final CustomerRelationshipTypeRepository relationshipTypeRepo;
    private final SalesforceClient salesforceClient;

    public CustomerProfileService(PartyRepository partyRepository,
                                   CustomerProfileAttributeRepository attributeRepo,
                                   CustomerProfileAttributeMappingRepository mappingRepo,
                                   CustomerProfileValueRepository valueRepo,
                                   CustomerRelationshipTypeRepository relationshipTypeRepo,
                                   SalesforceClient salesforceClient) {
        this.partyRepository = partyRepository;
        this.attributeRepo = attributeRepo;
        this.mappingRepo = mappingRepo;
        this.valueRepo = valueRepo;
        this.relationshipTypeRepo = relationshipTypeRepo;
        this.salesforceClient = salesforceClient;
    }

    /**
     * Resolves a customer by internal UUID or external ref (e.g. "CUST-RET-001").
     * The case-checklist "Fill Form" flow only carries the external ref in its
     * URL (partyRef), not the internal party UUID — this lets both callers use
     * the same endpoint instead of the frontend needing a separate lookup.
     */
    @Transactional(readOnly = true)
    public CustomerCrmProfileDto getProfileByIdOrExternalRef(String idOrExternalRef) {
        return getProfile(resolveParty(idOrExternalRef).getId());
    }

    private Party resolveParty(String idOrExternalRef) {
        try {
            UUID id = UUID.fromString(idOrExternalRef);
            return partyRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found: " + idOrExternalRef));
        } catch (IllegalArgumentException notAUuid) {
            return partyRepository.findByExternalId(idOrExternalRef)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found: " + idOrExternalRef));
        }
    }

    @Transactional(readOnly = true)
    public CustomerCrmProfileDto getProfile(UUID partyId) {
        Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found: " + partyId));

        List<CustomerProfileAttribute> attrs = attributeRepo.findByIsActiveTrueOrderBySortOrderAscLabelAsc().stream()
                .filter(a -> appliesToSegment(a.getSegments(), party.getPartyType()))
                .collect(Collectors.toList());
        Map<Integer, String> manualValues = valueRepo.findByPartyId(partyId).stream()
                .collect(Collectors.toMap(v -> v.getAttribute().getId(), CustomerProfileValue::getValue));

        CustomerCrmProfileDto dto = new CustomerCrmProfileDto();
        String lookupField = salesforceClient.getContactLookupField();
        Map<String, Map<String, Object>> crmValuesByObject = new HashMap<>();
        Set<String> matchedObjects = new HashSet<>();
        boolean hasCrmMappedAttr = attrs.stream().anyMatch(a -> "CRM_MAPPED".equals(a.getSource()));

        boolean available;
        String error = null;
        try {
            if (salesforceClient.isConfigured() && lookupField != null) {
                crmValuesByObject = fetchCrmValuesByObject(attrs, party.getExternalId(), lookupField, matchedObjects);
                available = true;
            } else {
                available = false;
                error = "Salesforce is not configured";
            }
        } catch (Exception e) {
            log.warn("[CustomerProfile] Salesforce fetch failed for party {}: {}", partyId, e.getMessage());
            available = false;
            error = e.getMessage();
        }
        dto.setSalesforceAvailable(available);
        dto.setSalesforceError(error);
        dto.setCrmMatched(available && hasCrmMappedAttr ? !matchedObjects.isEmpty() : null);
        Map<String, Map<String, Object>> finalCrmValues = crmValuesByObject;

        List<CustomerCrmProfileDto.Attribute> profile = new ArrayList<>();
        for (CustomerProfileAttribute attr : attrs) {
            Object value;
            if ("MANUAL".equals(attr.getSource())) {
                value = manualValues.get(attr.getId());
            } else {
                value = mappingRepo.findByAttribute_Id(attr.getId())
                        .map(m -> finalCrmValues.getOrDefault(m.getSalesforceObject(), Map.of())
                                .get(m.getSalesforceField()))
                        .orElse(null);
            }
            profile.add(new CustomerCrmProfileDto.Attribute(
                    attr.getKey(), attr.getLabel(), attr.getValueType(), attr.getSource(), value));
        }
        dto.setProfile(profile);

        List<CustomerCrmProfileDto.Relationship> relationships = new ArrayList<>();
        if (available) {
            List<CustomerRelationshipType> relevantTypes = relationshipTypeRepo.findByIsActiveTrueOrderBySortOrderAscNameAsc()
                    .stream().filter(t -> appliesToSegment(t.getSegments(), party.getPartyType()))
                    .collect(Collectors.toList());
            for (CustomerRelationshipType type : relevantTypes) {
                try {
                    relationships.add(fetchRelationship(type, party.getExternalId(), lookupField));
                } catch (Exception e) {
                    log.warn("[CustomerProfile] Relationship '{}' fetch failed for party {}: {}",
                            type.getName(), partyId, e.getMessage());
                }
            }
        }
        dto.setRelationships(relationships);

        return dto;
    }

    /** Blank/null segments on the attribute or relationship type means "applies to all segments". */
    private static boolean appliesToSegment(String segmentsCsv, String partyType) {
        if (segmentsCsv == null || segmentsCsv.isBlank()) return true;
        for (String s : segmentsCsv.split(",")) {
            if (s.trim().equalsIgnoreCase(partyType)) return true;
        }
        return false;
    }

    public void setManualValue(UUID partyId, String attributeKey, String value, String updatedBy) {
        CustomerProfileAttribute attr = attributeRepo.findByKey(attributeKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attribute not found: " + attributeKey));
        if (!"MANUAL".equals(attr.getSource()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Attribute '" + attributeKey + "' is CRM_MAPPED — cannot be set manually");
        if (!partyRepository.existsById(partyId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found: " + partyId);

        CustomerProfileValue pv = valueRepo.findByPartyIdAndAttribute_Id(partyId, attr.getId())
                .orElseGet(() -> {
                    CustomerProfileValue v = new CustomerProfileValue();
                    v.setPartyId(partyId);
                    v.setAttribute(attr);
                    return v;
                });
        pv.setValue(value);
        pv.setUpdatedBy(updatedBy);
        valueRepo.save(pv);
    }

    /**
     * One SOQL query per distinct Salesforce object referenced by CRM_MAPPED
     * attributes. matchedObjectsOut collects which of those objects actually
     * returned a record — lets the caller distinguish "linked, field just
     * happens to be blank" from "no Salesforce record for this customer".
     */
    private Map<String, Map<String, Object>> fetchCrmValuesByObject(
            List<CustomerProfileAttribute> attrs, String externalId, String lookupField, Set<String> matchedObjectsOut) {

        Map<String, Set<String>> fieldsByObject = new HashMap<>();
        for (CustomerProfileAttribute attr : attrs) {
            if (!"CRM_MAPPED".equals(attr.getSource())) continue;
            mappingRepo.findByAttribute_Id(attr.getId()).ifPresent(m ->
                    fieldsByObject.computeIfAbsent(m.getSalesforceObject(), k -> new LinkedHashSet<>())
                            .add(m.getSalesforceField()));
        }

        Map<String, Map<String, Object>> result = new HashMap<>();
        for (var entry : fieldsByObject.entrySet()) {
            String object = entry.getKey();
            String fields = String.join(", ", entry.getValue());
            String soql = "SELECT " + fields + " FROM " + object
                    + " WHERE " + lookupField + " = '" + SalesforceClient.escapeSoql(externalId) + "' LIMIT 1";
            List<Map<String, Object>> records = salesforceClient.query(soql);
            if (!records.isEmpty()) matchedObjectsOut.add(object);
            result.put(object, records.isEmpty() ? Map.of() : records.get(0));
        }
        return result;
    }

    /**
     * Tier B relationship fetch — two steps, because the child object's
     * "parent" field (e.g. Banking_Account__c.Contact__c) is a Salesforce
     * lookup storing a Salesforce Id, not the customer's external ref string:
     * first resolve the customer's own Salesforce Id on type.parentObject
     * (Contact for Retail, Account for SMB/Commercial), then query the
     * relationship object by that Id.
     */
    private CustomerCrmProfileDto.Relationship fetchRelationship(CustomerRelationshipType type, String externalId, String lookupField) {
        List<CustomerRelationshipAttribute> attrs = type.getAttributes();
        String fields = attrs.stream().map(CustomerRelationshipAttribute::getSalesforceField)
                .distinct().collect(Collectors.joining(", "));
        if (fields.isBlank()) {
            log.info("[CustomerProfile] Relationship type '{}' has no attributes configured — skipping without querying Salesforce", type.getName());
            return new CustomerCrmProfileDto.Relationship(type.getId(), type.getName(), List.of());
        }

        String parentSoql = "SELECT Id FROM " + type.getParentObject()
                + " WHERE " + lookupField + " = '" + SalesforceClient.escapeSoql(externalId) + "' LIMIT 1";
        List<Map<String, Object>> parentRecords = salesforceClient.query(parentSoql);
        if (parentRecords.isEmpty()) {
            log.info("[CustomerProfile] Relationship type '{}': no {} found where {} = '{}' — customer not linked on that object",
                    type.getName(), type.getParentObject(), lookupField, externalId);
            return new CustomerCrmProfileDto.Relationship(type.getId(), type.getName(), List.of());
        }
        String parentSalesforceId = String.valueOf(parentRecords.get(0).get("Id"));
        log.info("[CustomerProfile] Relationship type '{}': resolved {} Id={} for external ref '{}', querying {} where {} = that Id",
                type.getName(), type.getParentObject(), parentSalesforceId, externalId, type.getSalesforceObject(), type.getSalesforceParentField());

        String soql = "SELECT " + fields + " FROM " + type.getSalesforceObject()
                + " WHERE " + type.getSalesforceParentField() + " = '" + SalesforceClient.escapeSoql(parentSalesforceId) + "'";
        List<Map<String, Object>> raw = salesforceClient.query(soql);
        log.info("[CustomerProfile] Relationship type '{}': {} record(s) returned", type.getName(), raw.size());

        List<Map<String, Object>> records = raw.stream().map(r -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (CustomerRelationshipAttribute a : attrs) {
                mapped.put(a.getKey(), r.get(a.getSalesforceField()));
            }
            return mapped;
        }).collect(Collectors.toList());

        return new CustomerCrmProfileDto.Relationship(type.getId(), type.getName(), records);
    }
}
