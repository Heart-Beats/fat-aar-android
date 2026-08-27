package com.kezong.fataar

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

class NestedEmbedNode {

    Project parentProject
    Project childProject
    String requestedVariant
    SelectedVariantArtifact selection
    TaskProvider reBundleTask
    File finalAarFile

    NestedEmbedNode(Project parentProject,
                    Project childProject,
                    String requestedVariant,
                    SelectedVariantArtifact selection,
                    TaskProvider reBundleTask,
                    File finalAarFile) {
        this.parentProject = parentProject
        this.childProject = childProject
        this.requestedVariant = requestedVariant
        this.selection = selection
        this.reBundleTask = reBundleTask
        this.finalAarFile = finalAarFile
    }

    String getKey() {
        return "${childProject.path}@${selection.variant.name}"
    }
}
