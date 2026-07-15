package com.ecm.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generic paginated result wrapper for JDBC-based queries.
 * Compatible with the frontend's page/size/totalElements pattern.
 *
 * @param <T> the type of items in the page
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PagedResult<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    public static <T> PagedResult<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new PagedResult<>(content, page, size, totalElements, totalPages);
    }
}
