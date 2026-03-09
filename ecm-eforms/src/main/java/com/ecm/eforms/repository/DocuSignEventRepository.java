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

    /** Idempotency check — was this exact event type already received for this envelope? */
    boolean existsByEnvelopeIdAndEventType(String envelopeId, String eventType);

    /** Unprocessed events — for a retry scheduler */
    List<DocuSignEvent> findByProcessedFalseOrderByReceivedAtAsc();
}
