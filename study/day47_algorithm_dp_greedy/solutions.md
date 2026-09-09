# Day47 Algorithm: DP & Greedy · 题目与标准解答（Solutions）

> Java 21 可运行示例；重点在状态定义、遍历方向与正确性论证。

## Current

### Q1. 按当天索引定位并口述 I/O 边界（方法论题）。
DP 五要素：**状态定义、转移方程、初始化、遍历顺序、答案位置**；状态必须覆盖子问题所需的全部信息。

---

### Q2. 一维 0/1 背包（容量逆序）与完全背包反例。
```java
// 0/1 背包：每种物品最多取一次 → 容量必须【逆序】，避免同一物品被重复使用
int[] dp = new int[capacity + 1];
for (int i = 0; i < n; i++)
    for (int c = capacity; c >= weight[i]; c--)
        dp[c] = Math.max(dp[c], dp[c - weight[i]] + value[i]);
return dp[capacity];
```
**为什么逆序**：`dp[c-w[i]]` 必须还是“没考虑第 i 件物品”的旧值；逆序保证计算 c 时更小容量尚未被本物品更新。
**完全背包（可重复取）用正序**：
```java
for (int i = 0; i < n; i++)
    for (int c = weight[i]; c <= capacity; c++)     // 正序：允许沿本物品连续转移多次
        dp[c] = Math.max(dp[c], dp[c - weight[i]] + value[i]);
```
**完全背包错用逆序的反例**：物品重量 1、价值 1、容量 3，正序可得 dp[3]=3（取三次）；若逆序，dp[3] 只从旧 dp[2] 转移、本物品无法复用，结果只有 1，等价于 0/1 背包，错误。

---

### Q3. 不可达状态与 int/long 选择。
- 不可达用负无穷（`Integer.MIN_VALUE/4` 留余量防加溢出）或布尔标记，**不能误当 0**（0 可能是合法最优值）；
- 有乘法/最大和且范围大时用 `long`，输出前再按题意取模或转回；
- 转移前先判断前驱是否可达，避免从不合法状态推出错误答案。

---

### Q4. 区间 DP（按长度从短到长）。
```java
// 以“最长回文子序列”为例
public int longestPalindromeSubseq(String s) {
    int n = s.length(); int[][] f = new int[n][n];
    for (int i = 0; i < n; i++) f[i][i] = 1;        // 初始化：单字符
    for (int len = 2; len <= n; len++)              // 遍历顺序：区间长度短→长
        for (int l = 0; l + len - 1 < n; l++) {
            int r = l + len - 1;
            f[l][r] = s.charAt(l) == s.charAt(r)
                ? f[l+1][r-1] + 2
                : Math.max(f[l+1][r], f[l][r-1]);
        }
    return f[0][n-1];                               // 答案位置：整个区间
}
```
因为大区间依赖更短的子区间，必须保证计算 [l,r] 时 [l+1,r-1] 已算出，所以外层按长度递增。

---

### Q5. 回溯（选择、撤销、剪枝）。
```java
void backtrack(int[] nums, int start, List<Integer> path, List<List<Integer>> ans) {
    ans.add(new ArrayList<>(path));                // 每个节点都是答案（子集）
    for (int i = start; i < nums.length; i++) {
        path.add(nums[i]);                         // 选择
        backtrack(nums, i + 1, path, ans);
        path.remove(path.size() - 1);              // 撤销
        // 剪枝：排序后相邻相同可 continue 去重；超界可 break
    }
}
```

---

### Q6. 贪心的交换论证 / 最小反例。
贪心不能用“样例通过”证明正确，需要：
- **交换论证**：假设某个最优解第一步没选贪心选择，把其中元素与贪心选择交换，证明交换后解不变差，从而存在一个包含贪心选择的最优解，归纳即证。例：活动选择按最早结束时间贪心——把最优解的首个活动换成结束更早的活动，不会减少后续可选数量；
- **反例法（证伪）**：找最小反例说明贪心失败。例：0/1 背包按“性价比”贪心是错的，最小反例：容量 5，物品（重 3 值 5，性价比 1.67）、（重 3 值 5）、（重 2 值 3，性价比 1.5），贪心先选两个？实际选重量 3+2 组合可得 8，而单看性价比顺序会漏掉最优，证明必须 DP。

---

## External

### E1. 二维压一维。
二维 0/1 `f[i][c]=max(f[i-1][c], f[i-1][c-w]+v)` 压一维时，由于 f[i][c] 只依赖上一行的 c 与 c-w，容量逆序遍历即可原地覆盖旧值；若依赖同前行（完全背包）则正序。压缩后注意初始化语义是否仍对应“上一行”。

### E2. 复习节奏（1/3/7/14 天）。
当天写通并能口述五要素 → 第 1 天默写转移与遍历方向 → 第 3 天做一道同模型变式 → 第 7 天混合题型识别用哪种 DP → 第 14 天限时复现。重点复盘：遍历方向（能否复用元素）、不可达处理、答案取哪个位置。
