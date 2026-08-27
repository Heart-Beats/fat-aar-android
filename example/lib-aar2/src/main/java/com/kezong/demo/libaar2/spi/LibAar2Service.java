package com.kezong.demo.libaar2.spi;

import com.kezong.demo.spi.NestedEmbedService;

public final class LibAar2Service implements NestedEmbedService {
    @Override
    public String sourceModule() {
        return "lib-aar2";
    }
}
