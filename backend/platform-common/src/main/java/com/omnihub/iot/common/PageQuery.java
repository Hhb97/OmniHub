package com.omnihub.iot.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

public final class PageQuery {

    public static final int MAX_SIZE = 100;

    private PageQuery() {
    }

    public static int size(Integer size) {
        if (size == null || size < 1) {
            return 10;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static long page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    public static <T> Page<T> of(Integer page, Integer size) {
        return new Page<>(page(page), size(size));
    }
}
