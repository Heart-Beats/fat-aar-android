package com.kezong.demo.libaar.spi;

import com.kezong.demo.spi.NestedEmbedService;

public final class LibAarService implements NestedEmbedService {
    @Override
    public String sourceModule() {
        return "lib-aar";
    }
}
