package com.chat99.server.common;

import java.util.List;
import org.springframework.data.domain.Page;

/** 统一分页结构：content + page/size/totalElements/totalPages。 */
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, Page<?> source) {
        return new PageResponse<>(
            content,
            source.getNumber(),
            source.getSize(),
            source.getTotalElements(),
            source.getTotalPages());
    }
}
