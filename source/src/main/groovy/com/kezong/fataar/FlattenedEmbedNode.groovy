package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * 消费根视角下的一个扁平嵌入节点：后代项目 + 选中变体 + 自有薄产物。
 * direct 为 true 表示它是消费根的直接子模块，其薄产物承担子树非类内容的继承。
 */
class FlattenedEmbedNode {

    final Project project

    final LibraryVariant requestedVariant

    final SelectedVariantArtifact selection

    final boolean direct

    FlattenedEmbedNode(Project project,
                       LibraryVariant requestedVariant,
                       SelectedVariantArtifact selection,
                       boolean direct) {
        this.project = project
        this.requestedVariant = requestedVariant
        this.selection = selection
        this.direct = direct
    }

    String getKey() {
        return "${project.path}@${selection.variant.name}"
    }

    File getAarFile() {
        return selection.outputFile
    }

    TaskProvider getBundleTask() {
        return selection.bundleTask
    }
}
