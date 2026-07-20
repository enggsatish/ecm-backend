package com.ecm.admin.dto;

import java.util.List;

/**
 * A customer's canonical CRM profile — Tier A (flat attributes) and Tier B
 * (relationship lists: memberships, accounts). Always fetched live; never
 * persisted (except MANUAL-source Tier A values, which are ECM's own data).
 *
 * salesforceAvailable=false means Salesforce wasn't reachable/configured —
 * profile still renders with whatever MANUAL values exist; relationships is
 * empty. This is the "page still renders, profile shows Unavailable" decision
 * from the design review.
 */
public class CustomerCrmProfileDto {

    private boolean salesforceAvailable;
    private String salesforceError;
    /**
     * True/false only meaningful when salesforceAvailable=true: whether the
     * customer's external ref actually matched a record in the Salesforce
     * object(s) their segment's CRM-mapped attributes query. Null when there
     * are no CRM-mapped attributes for this customer's segment to check
     * against (nothing to match, not the same as "not found").
     */
    private Boolean crmMatched;
    private List<Attribute> profile;
    private List<Relationship> relationships;

    public boolean isSalesforceAvailable() { return salesforceAvailable; }
    public void setSalesforceAvailable(boolean salesforceAvailable) { this.salesforceAvailable = salesforceAvailable; }
    public String getSalesforceError() { return salesforceError; }
    public void setSalesforceError(String salesforceError) { this.salesforceError = salesforceError; }
    public Boolean getCrmMatched() { return crmMatched; }
    public void setCrmMatched(Boolean crmMatched) { this.crmMatched = crmMatched; }
    public List<Attribute> getProfile() { return profile; }
    public void setProfile(List<Attribute> profile) { this.profile = profile; }
    public List<Relationship> getRelationships() { return relationships; }
    public void setRelationships(List<Relationship> relationships) { this.relationships = relationships; }

    public static class Attribute {
        private String key;
        private String label;
        private String valueType;
        private String source;   // MANUAL | CRM_MAPPED
        private Object value;    // null if CRM_MAPPED and Salesforce unavailable

        public Attribute() {}
        public Attribute(String key, String label, String valueType, String source, Object value) {
            this.key = key; this.label = label; this.valueType = valueType;
            this.source = source; this.value = value;
        }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getValueType() { return valueType; }
        public void setValueType(String valueType) { this.valueType = valueType; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
    }

    public static class Relationship {
        private Integer typeId;
        private String typeName;
        private List<java.util.Map<String, Object>> records; // each: attributeKey -> value

        public Relationship() {}
        public Relationship(Integer typeId, String typeName, List<java.util.Map<String, Object>> records) {
            this.typeId = typeId; this.typeName = typeName; this.records = records;
        }
        public Integer getTypeId() { return typeId; }
        public void setTypeId(Integer typeId) { this.typeId = typeId; }
        public String getTypeName() { return typeName; }
        public void setTypeName(String typeName) { this.typeName = typeName; }
        public List<java.util.Map<String, Object>> getRecords() { return records; }
        public void setRecords(List<java.util.Map<String, Object>> records) { this.records = records; }
    }

    /** Body for PUT .../crm-profile/{attributeKey} — sets a MANUAL attribute's value. */
    public static class SetValueRequest {
        private String value;
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
