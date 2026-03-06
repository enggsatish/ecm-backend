package com.ecm.admin.service;

import com.ecm.admin.dto.RetentionPolicyDto;
import com.ecm.admin.entity.RetentionPolicy;
import com.ecm.admin.repository.RetentionPolicyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class RetentionPolicyService {

    private final RetentionPolicyRepository repo;

    public RetentionPolicyService(RetentionPolicyRepository repo) { this.repo = repo; }

    @Transactional(readOnly = true)
    public List<RetentionPolicyDto> listActive() {
        return repo.findByIsActiveTrueOrderByNameAsc().stream()
                .map(RetentionPolicyDto::from).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RetentionPolicyDto getById(Integer id) { return RetentionPolicyDto.from(findOrThrow(id)); }

    public RetentionPolicyDto create(RetentionPolicyDto.Request req) {
        validate(req);
        RetentionPolicy rp = new RetentionPolicy();
        rp.setName(req.getName()); rp.setCategoryId(req.getCategoryId());
        rp.setProductCode(req.getProductCode());
        rp.setArchiveAfterDays(req.getArchiveAfterDays());
        rp.setPurgeAfterDays(req.getPurgeAfterDays());
        return RetentionPolicyDto.from(repo.save(rp));
    }

    public RetentionPolicyDto update(Integer id, RetentionPolicyDto.Request req) {
        validate(req);
        RetentionPolicy rp = findOrThrow(id);
        rp.setName(req.getName()); rp.setCategoryId(req.getCategoryId());
        rp.setProductCode(req.getProductCode());
        rp.setArchiveAfterDays(req.getArchiveAfterDays());
        rp.setPurgeAfterDays(req.getPurgeAfterDays());
        rp.setUpdatedAt(OffsetDateTime.now());
        return RetentionPolicyDto.from(repo.save(rp));
    }

    public void deactivate(Integer id) {
        RetentionPolicy rp = findOrThrow(id);
        rp.setIsActive(false); rp.setUpdatedAt(OffsetDateTime.now()); repo.save(rp);
    }

    private void validate(RetentionPolicyDto.Request req) {
        if (req.getPurgeAfterDays() <= req.getArchiveAfterDays())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "purgeAfterDays must be greater than archiveAfterDays");
    }

    private RetentionPolicy findOrThrow(Integer id) {
        return repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Retention policy not found: " + id));
    }
}
