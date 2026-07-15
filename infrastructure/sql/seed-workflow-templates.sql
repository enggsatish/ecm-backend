-- ═══════════════════════════════════════════════════════════════════════════════
-- Seed Workflow Templates — run directly against live ecmdb
-- Inserts 3 templates + 1 new workflow_definition_config
-- Safe to re-run (ON CONFLICT DO NOTHING)
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. General Document Review (single-step backoffice)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_templates
    (name, description, process_key, dsl_definition, bpmn_xml, bpmn_source, status, version, is_default, sla_hours, warning_threshold_pct)
VALUES (
    'General Document Review',
    'Default single-step review by backoffice team. Document is reviewed and either approved or rejected.',
    'document-single-review',
    '{"processKey":"document-single-review","name":"General Document Review","steps":[{"id":"review","type":"USER_TASK","name":"Backoffice Review","candidateGroups":"ECM_BACKOFFICE","outcomes":["APPROVED","REJECTED"]}],"endStates":[{"id":"end_approved","name":"Approved","status":"APPROVED"},{"id":"end_rejected","name":"Rejected","status":"REJECTED"}]}'::jsonb,
    '<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
             targetNamespace="http://www.flowable.org/processdef">
  <process id="document-single-review" name="General Document Review" isExecutable="true">
    <startEvent id="start" name="Start" />
    <sequenceFlow id="flow_start" sourceRef="start" targetRef="review" />
    <userTask id="review" name="Backoffice Review"
              flowable:candidateGroups="ECM_BACKOFFICE"
              flowable:formKey="review-form">
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_review_gw" sourceRef="review" targetRef="gw_review" />
    <exclusiveGateway id="gw_review" name="Review Decision" />
    <sequenceFlow id="flow_approved" sourceRef="gw_review" targetRef="end_approved">
      <conditionExpression>${decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="flow_rejected" sourceRef="gw_review" targetRef="end_rejected">
      <conditionExpression>${decision == ''REJECTED''}</conditionExpression>
    </sequenceFlow>
    <endEvent id="end_approved" name="Approved">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>
    <endEvent id="end_rejected" name="Rejected">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="document-single-review">
      <bpmndi:BPMNShape id="start_di" bpmnElement="start">
        <omgdc:Bounds x="180" y="200" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="review_di" bpmnElement="review">
        <omgdc:Bounds x="300" y="178" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="gw_review_di" bpmnElement="gw_review" isMarkerVisible="true">
        <omgdc:Bounds x="530" y="193" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_approved_di" bpmnElement="end_approved">
        <omgdc:Bounds x="660" y="130" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_rejected_di" bpmnElement="end_rejected">
        <omgdc:Bounds x="660" y="270" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow_start_di" bpmnElement="flow_start">
        <omgdi:waypoint x="216" y="218" />
        <omgdi:waypoint x="300" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_review_gw_di" bpmnElement="flow_review_gw">
        <omgdi:waypoint x="460" y="218" />
        <omgdi:waypoint x="530" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_approved_di" bpmnElement="flow_approved">
        <omgdi:waypoint x="555" y="193" />
        <omgdi:waypoint x="555" y="148" />
        <omgdi:waypoint x="660" y="148" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_rejected_di" bpmnElement="flow_rejected">
        <omgdi:waypoint x="555" y="243" />
        <omgdi:waypoint x="555" y="288" />
        <omgdi:waypoint x="660" y="288" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>',
    'VISUAL', 'PUBLISHED', 1, TRUE, 48, 80
)
ON CONFLICT (process_key) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Underwriter Review (two-step: backoffice triage -> underwriter)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_templates
    (name, description, process_key, dsl_definition, bpmn_xml, bpmn_source, status, version, is_default, sla_hours, warning_threshold_pct)
VALUES (
    'Underwriter Review',
    'Two-step workflow: backoffice triage then underwriter approval. Underwriter can return to triage.',
    'document-dual-review',
    '{"processKey":"document-dual-review","name":"Underwriter Review","steps":[{"id":"triage","type":"USER_TASK","name":"Backoffice Triage","candidateGroups":"ECM_BACKOFFICE","outcomes":["FORWARD","REJECTED"]},{"id":"underwriter_review","type":"USER_TASK","name":"Underwriter Review","candidateGroups":"ECM_REVIEWER","outcomes":["APPROVED","REJECTED","RETURN"]}],"endStates":[{"id":"end_approved","name":"Approved","status":"APPROVED"},{"id":"end_rejected","name":"Rejected","status":"REJECTED"}]}'::jsonb,
    '<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
             targetNamespace="http://www.flowable.org/processdef">
  <process id="document-dual-review" name="Underwriter Review" isExecutable="true">
    <startEvent id="start" name="Start" />
    <sequenceFlow id="flow_start" sourceRef="start" targetRef="triage" />
    <userTask id="triage" name="Backoffice Triage"
              flowable:candidateGroups="ECM_BACKOFFICE"
              flowable:formKey="review-form">
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_triage_gw" sourceRef="triage" targetRef="gw_triage" />
    <exclusiveGateway id="gw_triage" name="Triage Decision" />
    <sequenceFlow id="flow_triage_forward" sourceRef="gw_triage" targetRef="underwriter_review">
      <conditionExpression>${decision == ''FORWARD'' || decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="flow_triage_reject" sourceRef="gw_triage" targetRef="end_rejected">
      <conditionExpression>${decision == ''REJECTED''}</conditionExpression>
    </sequenceFlow>
    <userTask id="underwriter_review" name="Underwriter Review"
              flowable:candidateGroups="ECM_REVIEWER"
              flowable:formKey="review-form">
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_uw_gw" sourceRef="underwriter_review" targetRef="gw_underwriter" />
    <exclusiveGateway id="gw_underwriter" name="Underwriter Decision" />
    <sequenceFlow id="flow_uw_approved" sourceRef="gw_underwriter" targetRef="end_approved">
      <conditionExpression>${decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="flow_uw_rejected" sourceRef="gw_underwriter" targetRef="end_rejected_uw">
      <conditionExpression>${decision == ''REJECTED''}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="flow_uw_return" sourceRef="gw_underwriter" targetRef="triage">
      <conditionExpression>${decision == ''RETURN''}</conditionExpression>
    </sequenceFlow>
    <endEvent id="end_approved" name="Approved">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>
    <endEvent id="end_rejected" name="Rejected (Triage)">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>
    <endEvent id="end_rejected_uw" name="Rejected (Underwriter)">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="document-dual-review">
      <bpmndi:BPMNShape id="start_di" bpmnElement="start">
        <omgdc:Bounds x="100" y="200" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="triage_di" bpmnElement="triage">
        <omgdc:Bounds x="210" y="178" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="gw_triage_di" bpmnElement="gw_triage" isMarkerVisible="true">
        <omgdc:Bounds x="430" y="193" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="underwriter_review_di" bpmnElement="underwriter_review">
        <omgdc:Bounds x="550" y="178" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="gw_underwriter_di" bpmnElement="gw_underwriter" isMarkerVisible="true">
        <omgdc:Bounds x="770" y="193" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_approved_di" bpmnElement="end_approved">
        <omgdc:Bounds x="900" y="130" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_rejected_di" bpmnElement="end_rejected">
        <omgdc:Bounds x="440" y="320" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_rejected_uw_di" bpmnElement="end_rejected_uw">
        <omgdc:Bounds x="900" y="270" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow_start_di" bpmnElement="flow_start">
        <omgdi:waypoint x="136" y="218" />
        <omgdi:waypoint x="210" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_triage_gw_di" bpmnElement="flow_triage_gw">
        <omgdi:waypoint x="370" y="218" />
        <omgdi:waypoint x="430" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_triage_forward_di" bpmnElement="flow_triage_forward">
        <omgdi:waypoint x="480" y="218" />
        <omgdi:waypoint x="550" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_triage_reject_di" bpmnElement="flow_triage_reject">
        <omgdi:waypoint x="455" y="243" />
        <omgdi:waypoint x="455" y="320" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_uw_gw_di" bpmnElement="flow_uw_gw">
        <omgdi:waypoint x="710" y="218" />
        <omgdi:waypoint x="770" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_uw_approved_di" bpmnElement="flow_uw_approved">
        <omgdi:waypoint x="795" y="193" />
        <omgdi:waypoint x="795" y="148" />
        <omgdi:waypoint x="900" y="148" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_uw_rejected_di" bpmnElement="flow_uw_rejected">
        <omgdi:waypoint x="795" y="243" />
        <omgdi:waypoint x="795" y="288" />
        <omgdi:waypoint x="900" y="288" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_uw_return_di" bpmnElement="flow_uw_return">
        <omgdi:waypoint x="770" y="218" />
        <omgdi:waypoint x="740" y="120" />
        <omgdi:waypoint x="290" y="120" />
        <omgdi:waypoint x="290" y="178" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>',
    'VISUAL', 'PUBLISHED', 1, FALSE, 24, 80
)
ON CONFLICT (process_key) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Form Submission -> Admin Triage -> Conditional Review
--
--    Form Filled -> Admin Triage (ECM_ADMIN)
--      ├─ TO_BACKOFFICE -> Backoffice Review (ECM_BACKOFFICE) -> Reviewer Approval (ECM_REVIEWER)
--      └─ TO_REVIEWER   -> Reviewer Approval (ECM_REVIEWER)
--
--    Reviewer outcomes:
--      ├─ APPROVED         -> End Approved
--      ├─ REJECTED         -> End Rejected
--      └─ ADDITIONAL_DOCS  -> Backoffice Review (loop back for more documents)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_templates
    (name, description, process_key, dsl_definition, bpmn_xml, bpmn_source, status, version, is_default, sla_hours, warning_threshold_pct)
VALUES (
    'Form Admin Triage Review',
    'Form submission triggers admin triage. Admin routes to backoffice or directly to reviewer. Reviewer can approve, reject, or send back to backoffice for additional documents.',
    'form-admin-triage-review',
    '{"processKey":"form-admin-triage-review","name":"Form Admin Triage Review","steps":[{"id":"admin_triage","type":"USER_TASK","name":"Admin Triage","candidateGroups":"ECM_ADMIN","outcomes":["TO_BACKOFFICE","TO_REVIEWER"]},{"id":"backoffice_review","type":"USER_TASK","name":"Backoffice Review","candidateGroups":"ECM_BACKOFFICE","outcomes":["FORWARD"]},{"id":"reviewer_approval","type":"USER_TASK","name":"Reviewer Approval","candidateGroups":"ECM_REVIEWER","outcomes":["APPROVED","REJECTED","ADDITIONAL_DOCS"]}],"endStates":[{"id":"end_approved","name":"Approved","status":"APPROVED"},{"id":"end_rejected","name":"Rejected","status":"REJECTED"}]}'::jsonb,
    '<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
             targetNamespace="http://www.flowable.org/processdef">

  <process id="form-admin-triage-review" name="Form Admin Triage Review" isExecutable="true">

    <!-- ═══ START ═══ -->
    <startEvent id="start" name="Form Submitted" />
    <sequenceFlow id="flow_start" sourceRef="start" targetRef="admin_triage" />

    <!-- ═══ STEP 1: Admin Triage ═══ -->
    <userTask id="admin_triage" name="Admin Triage"
              flowable:candidateGroups="ECM_ADMIN"
              flowable:formKey="review-form">
      <documentation>Admin reviews the submitted form and decides routing:
        TO_BACKOFFICE = needs backoffice document collection first
        TO_REVIEWER = ready for direct reviewer approval</documentation>
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>

    <sequenceFlow id="flow_triage_gw" sourceRef="admin_triage" targetRef="gw_triage" />
    <exclusiveGateway id="gw_triage" name="Routing Decision" />

    <!-- Route A: Admin -> Backoffice (also handles REJECTED/PASS from standard UI actions) -->
    <sequenceFlow id="flow_to_backoffice" sourceRef="gw_triage" targetRef="backoffice_review">
      <conditionExpression>${decision == ''TO_BACKOFFICE'' || decision == ''REJECTED'' || decision == ''PASS''}</conditionExpression>
    </sequenceFlow>

    <!-- Route B: Admin -> Reviewer directly (APPROVED from standard UI = route to reviewer) -->
    <sequenceFlow id="flow_to_reviewer" sourceRef="gw_triage" targetRef="reviewer_approval">
      <conditionExpression>${decision == ''TO_REVIEWER'' || decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>

    <!-- ═══ STEP 2: Backoffice Review (conditional) ═══ -->
    <userTask id="backoffice_review" name="Backoffice Review"
              flowable:candidateGroups="ECM_BACKOFFICE"
              flowable:formKey="review-form">
      <documentation>Backoffice collects/verifies documents, then forwards to reviewer.</documentation>
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>

    <sequenceFlow id="flow_bo_to_reviewer" sourceRef="backoffice_review" targetRef="reviewer_approval" />

    <!-- ═══ STEP 3: Reviewer Approval ═══ -->
    <userTask id="reviewer_approval" name="Reviewer Approval"
              flowable:candidateGroups="ECM_REVIEWER"
              flowable:formKey="review-form">
      <documentation>Final review and decision:
        APPROVED = application approved
        REJECTED = application denied
        ADDITIONAL_DOCS = send back to backoffice for more documents</documentation>
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>

    <sequenceFlow id="flow_reviewer_gw" sourceRef="reviewer_approval" targetRef="gw_reviewer" />
    <exclusiveGateway id="gw_reviewer" name="Reviewer Decision" />

    <!-- Approved -> End -->
    <sequenceFlow id="flow_rv_approved" sourceRef="gw_reviewer" targetRef="end_approved">
      <conditionExpression>${decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>

    <!-- Rejected -> End -->
    <sequenceFlow id="flow_rv_rejected" sourceRef="gw_reviewer" targetRef="end_rejected">
      <conditionExpression>${decision == ''REJECTED''}</conditionExpression>
    </sequenceFlow>

    <!-- Additional Docs -> Loop back to Backoffice -->
    <sequenceFlow id="flow_rv_additional" sourceRef="gw_reviewer" targetRef="backoffice_review">
      <conditionExpression>${decision == ''ADDITIONAL_DOCS''}</conditionExpression>
    </sequenceFlow>

    <!-- ═══ END STATES ═══ -->
    <endEvent id="end_approved" name="Approved">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>

    <endEvent id="end_rejected" name="Rejected">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>

  </process>

  <!-- ═══ DIAGRAM LAYOUT ═══ -->
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="form-admin-triage-review">

      <!-- Start -->
      <bpmndi:BPMNShape id="start_di" bpmnElement="start">
        <omgdc:Bounds x="80" y="250" width="36" height="36" />
      </bpmndi:BPMNShape>

      <!-- Admin Triage -->
      <bpmndi:BPMNShape id="admin_triage_di" bpmnElement="admin_triage">
        <omgdc:Bounds x="190" y="228" width="160" height="80" />
      </bpmndi:BPMNShape>

      <!-- Triage Gateway -->
      <bpmndi:BPMNShape id="gw_triage_di" bpmnElement="gw_triage" isMarkerVisible="true">
        <omgdc:Bounds x="420" y="243" width="50" height="50" />
      </bpmndi:BPMNShape>

      <!-- Backoffice Review (lower path) -->
      <bpmndi:BPMNShape id="backoffice_review_di" bpmnElement="backoffice_review">
        <omgdc:Bounds x="530" y="340" width="160" height="80" />
      </bpmndi:BPMNShape>

      <!-- Reviewer Approval (upper path) -->
      <bpmndi:BPMNShape id="reviewer_approval_di" bpmnElement="reviewer_approval">
        <omgdc:Bounds x="730" y="228" width="160" height="80" />
      </bpmndi:BPMNShape>

      <!-- Reviewer Gateway -->
      <bpmndi:BPMNShape id="gw_reviewer_di" bpmnElement="gw_reviewer" isMarkerVisible="true">
        <omgdc:Bounds x="960" y="243" width="50" height="50" />
      </bpmndi:BPMNShape>

      <!-- End Approved -->
      <bpmndi:BPMNShape id="end_approved_di" bpmnElement="end_approved">
        <omgdc:Bounds x="1090" y="180" width="36" height="36" />
      </bpmndi:BPMNShape>

      <!-- End Rejected -->
      <bpmndi:BPMNShape id="end_rejected_di" bpmnElement="end_rejected">
        <omgdc:Bounds x="1090" y="320" width="36" height="36" />
      </bpmndi:BPMNShape>

      <!-- ═══ EDGES ═══ -->
      <bpmndi:BPMNEdge id="flow_start_di" bpmnElement="flow_start">
        <omgdi:waypoint x="116" y="268" />
        <omgdi:waypoint x="190" y="268" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_triage_gw_di" bpmnElement="flow_triage_gw">
        <omgdi:waypoint x="350" y="268" />
        <omgdi:waypoint x="420" y="268" />
      </bpmndi:BPMNEdge>

      <!-- Admin -> Backoffice (down) -->
      <bpmndi:BPMNEdge id="flow_to_backoffice_di" bpmnElement="flow_to_backoffice">
        <omgdi:waypoint x="445" y="293" />
        <omgdi:waypoint x="445" y="380" />
        <omgdi:waypoint x="530" y="380" />
      </bpmndi:BPMNEdge>

      <!-- Admin -> Reviewer (straight) -->
      <bpmndi:BPMNEdge id="flow_to_reviewer_di" bpmnElement="flow_to_reviewer">
        <omgdi:waypoint x="470" y="268" />
        <omgdi:waypoint x="730" y="268" />
      </bpmndi:BPMNEdge>

      <!-- Backoffice -> Reviewer -->
      <bpmndi:BPMNEdge id="flow_bo_to_reviewer_di" bpmnElement="flow_bo_to_reviewer">
        <omgdi:waypoint x="690" y="380" />
        <omgdi:waypoint x="810" y="380" />
        <omgdi:waypoint x="810" y="308" />
      </bpmndi:BPMNEdge>

      <!-- Reviewer -> Gateway -->
      <bpmndi:BPMNEdge id="flow_reviewer_gw_di" bpmnElement="flow_reviewer_gw">
        <omgdi:waypoint x="890" y="268" />
        <omgdi:waypoint x="960" y="268" />
      </bpmndi:BPMNEdge>

      <!-- Approved -->
      <bpmndi:BPMNEdge id="flow_rv_approved_di" bpmnElement="flow_rv_approved">
        <omgdi:waypoint x="985" y="243" />
        <omgdi:waypoint x="985" y="198" />
        <omgdi:waypoint x="1090" y="198" />
      </bpmndi:BPMNEdge>

      <!-- Rejected -->
      <bpmndi:BPMNEdge id="flow_rv_rejected_di" bpmnElement="flow_rv_rejected">
        <omgdi:waypoint x="985" y="293" />
        <omgdi:waypoint x="985" y="338" />
        <omgdi:waypoint x="1090" y="338" />
      </bpmndi:BPMNEdge>

      <!-- Additional Docs -> loop back to Backoffice -->
      <bpmndi:BPMNEdge id="flow_rv_additional_di" bpmnElement="flow_rv_additional">
        <omgdi:waypoint x="960" y="268" />
        <omgdi:waypoint x="940" y="450" />
        <omgdi:waypoint x="610" y="450" />
        <omgdi:waypoint x="610" y="420" />
      </bpmndi:BPMNEdge>

    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>',
    'VISUAL', 'PUBLISHED', 1, FALSE, 48, 80
)
ON CONFLICT (process_key) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- Add workflow_definition_config for the new form triage workflow
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_definition_configs
    (name, description, process_key, assigned_role, is_active, sla_hours)
VALUES
    ('Form Admin Triage Review',
     'Form submission routed by admin to backoffice or reviewer. Reviewer can request additional documents.',
     'form-admin-triage-review', 'ECM_ADMIN', TRUE, 48)
ON CONFLICT DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- Also seed ECM_SUPER_ADMIN role if not present
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.roles (name, description, is_system)
VALUES ('ECM_SUPER_ADMIN', 'System-level super administrator', TRUE)
ON CONFLICT (name) DO NOTHING;
