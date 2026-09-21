package com.kezong.fataar

import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedArtifact

/**
 * 扁平图里的一个「产物级」节点：后代模块声明的原生 AAR / 远程 AAR / JAR。
 *
 * 这些产物既不是 Android library project（无法作为 [FlattenedEmbedNode] 展平），
 * 也不属于消费根自己的 embed 声明。若不显式收集，它们会随着「中间模块不再逐层合并」
 * 而从最终产物里消失（表现为宿主编译期找不到这些 SDK 的类）。
 */
class FlattenedEmbedArtifact {

    final Project owner

    final ResolvedArtifact artifact

    FlattenedEmbedArtifact(Project owner, ResolvedArtifact artifact) {
        this.owner = owner
        this.artifact = artifact
    }

    File getFile() {
        return artifact.file
    }

    boolean isJar() {
        return FatAarPlugin.ARTIFACT_TYPE_JAR == artifact.type
    }

    /** 去重键：同一坐标在不同节点重复声明时只保留一份。 */
    String getModuleKey() {
        return artifact.moduleVersion.id.group + ':' + artifact.moduleVersion.id.name
    }

    /** 解包目录名，需为文件系统与任务名安全。 */
    String getStorageKey() {
        return (artifact.moduleVersion.id.group + '__'
                + artifact.moduleVersion.id.name + '__'
                + artifact.moduleVersion.id.version)
                .replaceAll('[^A-Za-z0-9._-]', '_')
    }

    String getName() {
        return artifact.moduleVersion.id.name
    }
}
