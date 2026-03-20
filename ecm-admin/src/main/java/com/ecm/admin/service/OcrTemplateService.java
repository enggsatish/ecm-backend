package com.ecm.admin.service;

import com.ecm.admin.entity.OcrTemplate;
import com.ecm.admin.repository.OcrTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OcrTemplateService {

    private final OcrTemplateRepository repo;

    @Transactional(readOnly = true)
    public List<OcrTemplate> listAll() {
        return repo.findAll();
    }

    @Transactional(readOnly = true)
    public List<OcrTemplate> listActive() {
        return repo.findByIsActiveTrueOrderByCategoryCode();
    }

    @Transactional(readOnly = true)
    public OcrTemplate getById(Integer id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "OCR template not found: " + id));
    }

    public OcrTemplate create(OcrTemplate template) {
        if (repo.existsByCategoryCode(template.getCategoryCode().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Template already exists for category: " + template.getCategoryCode());
        template.setCategoryCode(template.getCategoryCode().toUpperCase());
        OcrTemplate saved = repo.save(template);
        log.info("OCR template created: id={}, category={}", saved.getId(), saved.getCategoryCode());
        return saved;
    }

    public OcrTemplate update(Integer id, OcrTemplate updates) {
        OcrTemplate existing = getById(id);
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getFields() != null) existing.setFields(updates.getFields());
        if (updates.getIsActive() != null) existing.setIsActive(updates.getIsActive());
        if (updates.getCategoryId() != null) existing.setCategoryId(updates.getCategoryId());
        OcrTemplate saved = repo.save(existing);
        log.info("OCR template updated: id={}", id);
        return saved;
    }

    public void delete(Integer id) {
        OcrTemplate t = getById(id);
        t.setIsActive(false);
        repo.save(t);
        log.info("OCR template deactivated: id={}", id);
    }
}
