package com.ecm.eforms.mapper;

import com.ecm.eforms.model.dto.EFormsDtos.*;
import com.ecm.eforms.model.entity.FormDefinition;
import com.ecm.eforms.model.entity.FormSubmission;
import org.mapstruct.*;

import java.util.List;

/**
 * MapStruct mapper — entity ↔ DTO.
 * Generated at compile time. Lombok runs first (configured in pom.xml).
 */
@Mapper(
    componentModel = "spring",
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface FormMapper {

    // ── FormDefinition ────────────────────────────────────────────────

    FormDefinitionDto toDto(FormDefinition entity);

    @Mapping(target = "submissionCount", ignore = true)
    FormDefinitionSummary toSummary(FormDefinition entity);

    List<FormDefinitionSummary> toSummaryList(List<FormDefinition> entities);

    // ── FormSubmission ────────────────────────────────────────────────

    @Mapping(target = "formDefinitionId", source = "formDefinition.id")
    FormSubmissionDto toSubmissionDto(FormSubmission entity);

    @Mapping(target = "formName", ignore = true)
    FormSubmissionSummary toSubmissionSummary(FormSubmission entity);

    List<FormSubmissionSummary> toSubmissionSummaryList(List<FormSubmission> entities);
}
