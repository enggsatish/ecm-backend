package com.ecm.document.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Generic paged wrapper consistent with the ApiResponse&lt;T&gt; pattern
 * used across the platform.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}