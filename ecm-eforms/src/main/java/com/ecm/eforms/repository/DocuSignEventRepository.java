package com.ecm.eforms.repository;

import com.ecm.eforms.model.entity.DocuSignEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocuSignEventRepository extends JpaRepository<DocuSignEvent, UUID> {

    /** All events for an envelope — ordered for audit trail */
    List<DocuSignEvent> findByEnvelopeIdOrderByReceivedAtAsc(String envelopeId);

    /** Unprocessed events — for a retry scheduler (Sprint 2) */
    List<DocuSignEvent> findByProcessedFalseOrderByReceivedAtAsc();
}
