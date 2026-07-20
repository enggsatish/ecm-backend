package com.ecm.admin.service;

import com.ecm.admin.dto.ProfileAttributeDto;
import com.ecm.admin.dto.RelationshipTypeDto;
import com.ecm.admin.entity.CustomerProfileAttribute;
import com.ecm.admin.entity.CustomerProfileAttributeMapping;
import com.ecm.admin.entity.CustomerRelationshipAttribute;
import com.ecm.admin.entity.CustomerRelationshipType;
import com.ecm.admin.repository.CustomerProfileAttributeMappingRepository;
import com.ecm.admin.repository.CustomerProfileAttributeRepository;
import com.ecm.admin.repository.CustomerRelationshipAttributeRepository;
import com.ecm.admin.repository.CustomerRelationshipTypeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Superadmin CRUD for the CRM-aware form-fill data model — Layer 1 (canonical
 * profile attributes), Layer 2 (their Salesforce mappings), and Tier B
 * (customer relationship types + their own attribute schemas). See design
 * note "CRM-Aware Form Fill & Customer 360" (2026-07-17).
 */
@Service
@Transactional
public class CustomerProfileSchemaService {

    private static final List<String> VALUE_TYPES = List.of("STRING", "DATE", "EMAIL", "PHONE", "NUMBER");
    private static final List<String> SOURCES = List.of("MANUAL", "CRM_MAPPED");

    private final CustomerProfileAttributeRepository attributeRepo;
    private final CustomerProfileAttributeMappingRepository mappingRepo;
    private final CustomerRelationshipTypeRepository relationshipTypeRepo;
    private final CustomerRelationshipAttributeRepository relationshipAttributeRepo;

    public CustomerProfileSchemaService(CustomerProfileAttributeRepository attributeRepo,
                                         CustomerProfileAttributeMappingRepository mappingRepo,
                                         CustomerRelationshipTypeRepository relationshipTypeRepo,
                                         CustomerRelationshipAttributeRepository relationshipAttributeRepo) {
        this.attributeRepo = attributeRepo;
        this.mappingRepo = mappingRepo;
        this.relationshipTypeRepo = relationshipTypeRepo;
        this.relationshipAttributeRepo = relationshipAttributeRepo;
    }

    // ── Profile attributes (Layer 1 + 2) ───────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProfileAttributeDto> listAttributes() {
        return attributeRepo.findByIsActiveTrueOrderBySortOrderAscLabelAsc().stream()
                .map(a -> ProfileAttributeDto.from(a, mappingRepo.findByAttribute_Id(a.getId()).orElse(null)))
                .collect(Collectors.toList());
    }

    public ProfileAttributeDto createAttribute(ProfileAttributeDto.Request req) {
        validateAttributeRequest(req);
        if (attributeRepo.existsByKey(req.getKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attribute key already exists: " + req.getKey());

        CustomerProfileAttribute attr = new CustomerProfileAttribute();
        applyAttributeRequest(attr, req);
        attr = attributeRepo.save(attr);

        CustomerProfileAttributeMapping mapping = applyMapping(attr, req);
        return ProfileAttributeDto.from(attr, mapping);
    }

    public ProfileAttributeDto updateAttribute(Integer id, ProfileAttributeDto.Request req) {
        validateAttributeRequest(req);
        CustomerProfileAttribute attr = findAttributeOrThrow(id);
        if (!attr.getKey().equals(req.getKey()) && attributeRepo.existsByKey(req.getKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attribute key already exists: " + req.getKey());
        applyAttributeRequest(attr, req);
        attr = attributeRepo.save(attr);

        CustomerProfileAttributeMapping mapping;
        if ("CRM_MAPPED".equals(req.getSource())) {
            mapping = applyMapping(attr, req);
        } else {
            mappingRepo.deleteByAttribute_Id(attr.getId());
            mapping = null;
        }
        return ProfileAttributeDto.from(attr, mapping);
    }

    public void deactivateAttribute(Integer id) {
        CustomerProfileAttribute attr = findAttributeOrThrow(id);
        attr.setIsActive(false);
        attributeRepo.save(attr);
    }

    private CustomerProfileAttributeMapping applyMapping(CustomerProfileAttribute attr, ProfileAttributeDto.Request req) {
        if (!"CRM_MAPPED".equals(req.getSource())) return null;
        if (req.getSalesforceObject() == null || req.getSalesforceObject().isBlank()
                || req.getSalesforceField() == null || req.getSalesforceField().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "salesforceObject and salesforceField are required when source=CRM_MAPPED");
        }
        CustomerProfileAttributeMapping mapping = mappingRepo.findByAttribute_Id(attr.getId())
                .orElseGet(CustomerProfileAttributeMapping::new);
        mapping.setAttribute(attr);
        mapping.setSalesforceObject(req.getSalesforceObject().trim());
        mapping.setSalesforceField(req.getSalesforceField().trim());
        return mappingRepo.save(mapping);
    }

    private void applyAttributeRequest(CustomerProfileAttribute attr, ProfileAttributeDto.Request req) {
        attr.setKey(req.getKey().trim());
        attr.setLabel(req.getLabel().trim());
        attr.setValueType(req.getValueType());
        attr.setSource(req.getSource());
        attr.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        attr.setSegments(normalizeSegments(req.getSegments()));
    }

    private void validateAttributeRequest(ProfileAttributeDto.Request req) {
        if (!VALUE_TYPES.contains(req.getValueType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid valueType: " + req.getValueType());
        if (!SOURCES.contains(req.getSource()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source: " + req.getSource());
    }

    private CustomerProfileAttribute findAttributeOrThrow(Integer id) {
        return attributeRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile attribute not found: " + id));
    }

    // ── Relationship types (Tier B) ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RelationshipTypeDto> listRelationshipTypes() {
        return relationshipTypeRepo.findByIsActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(RelationshipTypeDto::from).collect(Collectors.toList());
    }

    public RelationshipTypeDto createRelationshipType(RelationshipTypeDto.Request req) {
        if (relationshipTypeRepo.existsByName(req.getName()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Relationship type already exists: " + req.getName());
        CustomerRelationshipType type = new CustomerRelationshipType();
        applyRelationshipTypeRequest(type, req);
        return RelationshipTypeDto.from(relationshipTypeRepo.save(type));
    }

    public RelationshipTypeDto updateRelationshipType(Integer id, RelationshipTypeDto.Request req) {
        CustomerRelationshipType type = findRelationshipTypeOrThrow(id);
        if (!type.getName().equals(req.getName()) && relationshipTypeRepo.existsByName(req.getName()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Relationship type already exists: " + req.getName());
        applyRelationshipTypeRequest(type, req);
        return RelationshipTypeDto.from(relationshipTypeRepo.save(type));
    }

    public void deactivateRelationshipType(Integer id) {
        CustomerRelationshipType type = findRelationshipTypeOrThrow(id);
        type.setIsActive(false);
        relationshipTypeRepo.save(type);
    }

    private void applyRelationshipTypeRequest(CustomerRelationshipType type, RelationshipTypeDto.Request req) {
        type.setName(req.getName().trim());
        type.setSalesforceObject(req.getSalesforceObject().trim());
        type.setSalesforceParentField(req.getSalesforceParentField().trim());
        type.setParentObject(req.getParentObject().trim());
        type.setSegments(normalizeSegments(req.getSegments()));
        type.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
    }

    /** Blank/whitespace-only segments means "applies to all" — store as null, not "". */
    private String normalizeSegments(String segments) {
        return (segments == null || segments.isBlank()) ? null : segments.trim();
    }

    private CustomerRelationshipType findRelationshipTypeOrThrow(Integer id) {
        return relationshipTypeRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relationship type not found: " + id));
    }

    // ── Relationship attributes (sub-schema per type) ──────────────────────────

    public RelationshipTypeDto.Attribute addRelationshipAttribute(Integer typeId, RelationshipTypeDto.Attribute.Request req) {
        CustomerRelationshipType type = findRelationshipTypeOrThrow(typeId);
        if (!VALUE_TYPES.contains(req.getValueType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid valueType: " + req.getValueType());

        CustomerRelationshipAttribute attr = new CustomerRelationshipAttribute();
        attr.setRelationshipType(type);
        attr.setKey(req.getKey().trim());
        attr.setLabel(req.getLabel().trim());
        attr.setValueType(req.getValueType());
        attr.setSalesforceField(req.getSalesforceField().trim());
        attr.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
        return RelationshipTypeDto.Attribute.from(relationshipAttributeRepo.save(attr));
    }

    public void removeRelationshipAttribute(Integer typeId, Integer attributeId) {
        CustomerRelationshipAttribute attr = relationshipAttributeRepo.findById(attributeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Relationship attribute not found: " + attributeId));
        if (!attr.getRelationshipType().getId().equals(typeId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attribute does not belong to relationship type " + typeId);
        relationshipAttributeRepo.delete(attr);
    }
}
