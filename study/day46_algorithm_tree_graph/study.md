# 必须会背会写

- DFS 使用递归或显式栈，BFS 使用队列；`visited` 防止重复访问和环路，树递归必须有空节点终止
- `PriorityQueue` 堆顶读取 O(1)、插入/删除 O(log n)；并查集通过路径压缩和按秩合并接近常数
- Kahn 拓扑排序用入度和队列，只有 DAG 能得到完整拓扑序；无权最短路用 BFS，非负权用 Dijkstra
- 邻接表遍历复杂度通常是 O(V+E)，矩阵空间是 O(V²)，算法选择受稀疏/稠密图影响
- BFS 代码骨架是：
  ```java
  Queue<Integer> q = new ArrayDeque<>();
  q.add(source); visited[source] = true;
  while (!q.isEmpty()) for (int i = q.size(); i > 0; i--) {
      int u = q.remove();
      for (int v : graph[u]) if (!visited[v]) { visited[v] = true; q.add(v); }
  }
  ```
- 外部源码索引（会背会写）：[Java ArrayDeque](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/ArrayDeque.html)、[PriorityQueue](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/PriorityQueue.html)；[树/图 INDEX](../../../AI_Infra/Phases_book/algorithm/4_链表_树与_LCA/INDEX.md)

# 必须理解

- 树是无环连通图；递归深度过大可能耗尽 Java 栈，图搜索必须明确有向/无向、重复边、断开图和权值条件
- 外部源码索引（必须理解）：[CP-Algorithms BFS](https://cp-algorithms.com/graph/breadth-first-search.html)、[Dijkstra](https://cp-algorithms.com/graph/dijkstra.html)、[DSU](https://cp-algorithms.com/data_structures/disjoint_set_union.html)
- 题面索引：[树与 LCA INDEX](../../../AI_Infra/Phases_book/algorithm/4_链表_树与_LCA/INDEX.md)、[图论与最短路 INDEX](../../../AI_Infra/Phases_book/algorithm/5_图论与最短路/INDEX.md)；官方：[Graph](https://cp-algorithms.com/graph/)
