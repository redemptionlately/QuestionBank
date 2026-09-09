# Day46 Algorithm: Tree & Graph · 题目与标准解答（Solutions）

> Java 21 可运行示例；给出状态、复杂度与前置条件。

## Current

### Q1. 按当天索引定位并口述 I/O 边界（方法论题）。
先明确图是有向/无向、是否有重复边、是否连通、边权是否非负，再决定用 BFS / Dijkstra / 拓扑；树递归必须有空节点终止条件。

---

### Q2. DFS（递归与显式栈）。
```java
// 递归 DFS（二叉树），空节点终止，防止 NPE/无限递归
void dfs(TreeNode node, List<Integer> out) {
    if (node == null) return;
    out.add(node.val);
    dfs(node.left, out);
    dfs(node.right, out);
}
// 显式栈（等价前序，避免深树耗尽 Java 栈）
void dfsIter(TreeNode root) {
    Deque<TreeNode> st = new ArrayDeque<>();
    if (root != null) st.push(root);
    while (!st.isEmpty()) {
        TreeNode u = st.pop();
        if (u.right != null) st.push(u.right); // 先压右后压左，保证左先处理
        if (u.left != null) st.push(u.left);
    }
}
```
图 DFS 必须配 `visited`，否则有环会无限循环。

---

### Q3. 邻接表 BFS（visited 入队前 vs 出队后）。
```java
List<Integer> bfs(List<Integer>[] graph, int source) {
    boolean[] visited = new boolean[graph.length];
    List<Integer> order = new ArrayList<>();
    Queue<Integer> q = new ArrayDeque<>();
    q.add(source); visited[source] = true;      // ★ 入队即标记
    while (!q.isEmpty()) {
        for (int i = q.size(); i > 0; i--) {    // 逐层（需要层数时用该写法）
            int u = q.remove();
            order.add(u);
            for (int v : graph[u])
                if (!visited[v]) { visited[v] = true; q.add(v); }
        }
    }
    return order;
}
```
**差异**：在**入队前**标记 visited 可保证每个节点只入队一次（推荐）；若改成“取出队列后才标记”，同一节点可能被多个邻接点重复入队（虽结果仍可遍历但队列膨胀、重复处理）。邻接表遍历 O(V+E)，邻接矩阵 O(V²)。

---

### Q4. 堆 / 并查集 / 拓扑排序 / Dijkstra 的状态与复杂度。
**PriorityQueue**：堆顶 peek O(1)，offer/poll O(log n)，默认小顶堆。
**并查集 DSU**（路径压缩 + 按秩合并，均摊近 O(α(n))≈常数）：
```java
class DSU {
    int[] p, sz;
    DSU(int n){ p = new int[n]; sz = new int[n]; Arrays.setAll(p, i -> i); Arrays.fill(sz,1); }
    int find(int x){ return p[x]==x ? x : (p[x]=find(p[x])); }
    boolean union(int a,int b){ a=find(a); b=find(b); if(a==b)return false;
        if(sz[a]<sz[b]){int t=a;a=b;b=t;} p[b]=a; sz[a]+=sz[b]; return true; }
}
```
**Kahn 拓扑排序**（只有 DAG 能得到完整序）：统计入度 → 入度 0 入队 → 出队并把邻接点入度 -1，归零入队 → 最终输出数 < 顶点数即存在环。O(V+E)。
**Dijkstra（非负权单源最短路）**：
```java
int[] dist = new int[n]; Arrays.fill(dist, Integer.MAX_VALUE);
dist[s] = 0;
PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));
pq.offer(new int[]{s, 0});
while (!pq.isEmpty()) {
    int[] cur = pq.poll(); int u = cur[0], d = cur[1];
    if (d != dist[u]) continue;                 // 过期松弛项跳过
    for (int[] e : adj[u]) { int v=e[0], w=e[1];
        if (dist[v] > d + w) { dist[v] = d + w; pq.offer(new int[]{v, dist[v]}); }
    }
}
```
二叉堆实现 O((V+E)log V)；有负权需 Bellman-Ford/SPFA。

---

### Q5. 比较无权 BFS、Dijkstra、拓扑排序。
| 算法 | 前置条件 | 解决问题 | 复杂度 |
|---|---|---|---|
| BFS | 无权图（或边权相同） | 最少边数最短路、层序 | O(V+E) |
| Dijkstra | 边权**非负** | 加权单源最短路 | O((V+E)logV) |
| Kahn 拓扑 | 有向无环图 DAG | 线性先后序、环检测 | O(V+E) |
树是无环连通图（V-1 条边）；递归深度可能达 O(n)（斜树），必要时改显式栈或莫里斯遍历。

---

## External

### E1. 递归改显式栈。
任何树/图递归都可用“栈帧对象（节点 + 处理阶段）”改写：模拟调用栈，进入时压栈、返回时弹栈，避免递归深度超过 `-Xss` 导致 StackOverflowError；中序等需要“先左后处理再右”时用两次入栈或 visited 标记区分首次访问与回溯。

### E2. 断开图/重复边/环测试。
- 断开图：从单源 BFS 后仍有未访问节点（多源或外层循环每个未访问点都起一次遍历）；
- 重复边：邻接表会列出两次，visited 保证只处理一次；DSU union 返回 false 即发现重复连接/成环；
- 环：拓扑排序输出不足 V 个即有环；无向图 DFS 遇到已访问且非父节点即回边成环。
