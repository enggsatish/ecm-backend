package com.ecm.admin.service;

import com.ecm.admin.config.AdminRabbitConfig;
import com.ecm.admin.dto.CategoryDto;
import com.ecm.admin.entity.DocumentCategory;
import com.ecm.admin.repository.DocumentCategoryRepository;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class DocumentCategoryService {

    private final DocumentCategoryRepository repo;
    private final RabbitTemplate rabbit;

    public DocumentCategoryService(DocumentCategoryRepository repo, RabbitTemplate rabbit) {
        this.repo = repo;
        this.rabbit = rabbit;
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> listTree() {
        return repo.findByParentIsNullAndIsActiveTrueOrderByNameAsc()
                .stream().map(CategoryDto::from).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> listFlat() {
        return repo.findByIsActiveTrueOrderByNameAsc()
                .stream().map(CategoryDto::flat).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CategoryDto getById(Integer id) { return CategoryDto.flat(findOrThrow(id)); }

    public CategoryDto create(CategoryDto.Request req) {
        if (repo.existsByCode(req.getCode().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Category code already exists: " + req.getCode());
        DocumentCategory cat = new DocumentCategory();
        cat.setName(req.getName().trim());
        cat.setCode(req.getCode().toUpperCase().trim());
        cat.setDescription(req.getDescription());
        if (req.getParentId() != null) cat.setParent(findOrThrow(req.getParentId()));
        DocumentCategory saved = repo.save(cat);
        publishEvent(saved.getId(), saved.getCode(), "CREATED");
        return CategoryDto.flat(saved);
    }

    public CategoryDto update(Integer id, CategoryDto.Request req) {
        DocumentCategory cat = findOrThrow(id);
        cat.setName(req.getName().trim());
        cat.setDescription(req.getDescription());
        cat.setUpdatedAt(OffsetDateTime.now());
        String newCode = req.getCode().toUpperCase().trim();
        if (!cat.getCode().equals(newCode) && repo.existsByCode(newCode))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Category code already in use: " + newCode);
        cat.setCode(newCode);
        if (req.getParentId() != null) {
            if (req.getParentId().equals(id))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Category cannot be its own parent");
            cat.setParent(findOrThrow(req.getParentId()));
        } else {
            cat.setParent(null);
        }
        DocumentCategory saved = repo.save(cat);
        publishEvent(saved.getId(), saved.getCode(), "UPDATED");
        return CategoryDto.flat(saved);
    }

    public void deactivate(Integer id) {
        DocumentCategory cat = findOrThrow(id);
        cat.setIsActive(false);
        cat.setUpdatedAt(OffsetDateTime.now());
        repo.save(cat);
        publishEvent(id, cat.getCode(), "DEACTIVATED");
    }

    private void publishEvent(Integer categoryId, String code, String action) {
        try {
            rabbit.convertAndSend(AdminRabbitConfig.ADMIN_EXCHANGE,
                    AdminRabbitConfig.RK_CATEGORY_UPDATED,
                    Map.of("categoryId", categoryId, "categoryCode", code, "action", action));
        } catch (Exception e) {
            LoggerFactory.getLogger(getClass())
                    .warn("Failed to publish category event: {}", e.getMessage());
        }
    }

    private DocumentCategory findOrThrow(Integer id) {
        return repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + id));
    }
}
