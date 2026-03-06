package com.ecm.admin.service;

import com.ecm.admin.dto.DepartmentDto;
import com.ecm.admin.dto.DepartmentRequest;
import com.ecm.admin.entity.Department;
import com.ecm.admin.repository.DepartmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class DepartmentService {

    private final DepartmentRepository repo;

    public DepartmentService(DepartmentRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<DepartmentDto> listTree() {
        return repo.findByParentIsNullAndIsActiveTrueOrderByNameAsc()
                .stream().map(DepartmentDto::from).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DepartmentDto> listFlat() {
        return repo.findByIsActiveTrueOrderByNameAsc()
                .stream().map(DepartmentDto::flat).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DepartmentDto getById(Integer id) {
        return DepartmentDto.flat(findOrThrow(id));
    }

    public DepartmentDto create(DepartmentRequest req) {
        if (repo.existsByCode(req.getCode().toUpperCase()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Department code already exists: " + req.getCode());
        Department dept = new Department();
        dept.setName(req.getName().trim());
        dept.setCode(req.getCode().toUpperCase().trim());
        if (req.getParentId() != null) dept.setParent(findOrThrow(req.getParentId()));
        return DepartmentDto.flat(repo.save(dept));
    }

    public DepartmentDto update(Integer id, DepartmentRequest req) {
        Department dept = findOrThrow(id);
        dept.setName(req.getName().trim());
        dept.setUpdatedAt(OffsetDateTime.now());
        String newCode = req.getCode().toUpperCase().trim();
        if (!dept.getCode().equals(newCode) && repo.existsByCode(newCode))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Department code already in use: " + newCode);
        dept.setCode(newCode);
        if (req.getParentId() != null) {
            if (req.getParentId().equals(id))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Department cannot be its own parent");
            dept.setParent(findOrThrow(req.getParentId()));
        } else {
            dept.setParent(null);
        }
        return DepartmentDto.flat(repo.save(dept));
    }

    public void deactivate(Integer id) {
        Department dept = findOrThrow(id);
        dept.setIsActive(false);
        dept.setUpdatedAt(OffsetDateTime.now());
        repo.save(dept);
    }

    private Department findOrThrow(Integer id) {
        return repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found: " + id));
    }
}
