cd ecm-platform/ecm-identity
mvn spring-boot:run

cd ecm-platform/ecm-document
mvn spring-boot:run

cd ecm-platform/ecm-gateway
mvn spring-boot:run

curl http://localhost:8081/actuator/health