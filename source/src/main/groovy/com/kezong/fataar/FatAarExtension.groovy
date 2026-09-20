package com.kezong.fataar;

class FatAarExtension {

    /**
     * 扩展名，也是 build.gradle 中 {@code fataar { }} 块的名称。
     */
    static final String NAME = "fataar"

    /**
     * Plan A: rewrite R references in the merged classes archive ({@code rewriteRClasses}).
     * Plan B: generate sub module's R class to process the merging problem of R files.
     * if transformR is true, use Plan A, else use Plan B.
     * In the future, Plan B maybe deprecated.
     * @since 1.3.0
     */
    boolean transformR = true

    /**
     * If transitive is true, local jar module and remote library's dependencies will be embed. (local aar module does not support)
     * If transitive is false, just embed first level dependency
     * Default value is false
     * @since 1.3.0
     */
    boolean transitive = false
}
