package com.kezong.fataar

/**
 * 校验结果载体。过渡期同时携带旧的嵌套节点（阶段一消费语义）与新的扁平节点列表；
 * 任务 6 删除嵌套部分后只保留 flattenedNodes。
 */
class NestedEmbedGraph {

    final Collection<NestedEmbedNode> nestedNodes

    final Collection<FlattenedEmbedNode> flattenedNodes

    NestedEmbedGraph(Collection<NestedEmbedNode> nestedNodes,
                     Collection<FlattenedEmbedNode> flattenedNodes) {
        this.nestedNodes = nestedNodes == null ? Collections.emptyList() : nestedNodes
        this.flattenedNodes = flattenedNodes == null ? Collections.emptyList() : flattenedNodes
    }
}
