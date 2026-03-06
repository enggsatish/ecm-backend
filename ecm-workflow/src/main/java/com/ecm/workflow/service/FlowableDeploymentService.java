package com.ecm.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Deploys generated BPMN XML to the Flowable engine at runtime.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowableDeploymentService {

    private final RepositoryService repositoryService;

    public record DeploymentResult(
            String deploymentId,
            String processDefinitionId,
            String processDefinitionKey,
            int version
    ) {}

    /**
     * Deploys BPMN XML and returns the resulting process definition metadata.
     * Flowable auto-increments the version if the same processKey was deployed before.
     */
    public DeploymentResult deploy(String bpmnXml, String templateName) {
        log.info("Deploying BPMN for template: {}", templateName);

        Deployment deployment = repositoryService.createDeployment()
                .name(templateName)
                .addInputStream(
                        sanitizeKey(templateName) + ".bpmn20.xml",
                        new ByteArrayInputStream(bpmnXml.getBytes(StandardCharsets.UTF_8))
                )
                .deploy();

        ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();

        log.info("Deployed process: {} v{} (deploymentId={})",
                def.getKey(), def.getVersion(), deployment.getId());

        return new DeploymentResult(
                deployment.getId(),
                def.getId(),
                def.getKey(),
                def.getVersion()
        );
    }

    /** Undeploy a specific deployment (e.g. when template is deprecated) */
    public void undeploy(String deploymentId) {
        repositoryService.deleteDeployment(deploymentId, false);
        log.info("Undeployed deployment: {}", deploymentId);
    }

    private String sanitizeKey(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}