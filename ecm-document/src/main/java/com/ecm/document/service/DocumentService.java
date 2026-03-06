package com.ecm.document.service;

import com.ecm.document.dto.DocumentResponse;
import com.ecm.document.dto.DocumentUploadRequest;
import com.ecm.document.dto.PagedResponse;
import com.ecm.document.entity.Document;
import com.ecm.document.entity.DocumentStatus;
import com.ecm.document.storage.StorageObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DocumentService {

    /**
     * @param uploadedByUserId  integer PK from ecm_core.users — resolved by the controller
     *                          from the JWT subject via the user lookup service
     */
    DocumentResponse upload(MultipartFile file,
                            DocumentUploadRequest metadata,
                            Integer uploadedByUserId,
                            String  uploadedByEmail);
    PagedResponse<DocumentResponse> search(String query, Pageable pageable);

    PagedResponse<DocumentResponse> listAll(Pageable pageable);

    DocumentResponse getById(UUID id);

    StorageObject download(UUID id);

    void delete(UUID id, Integer deletedByUserId);
}