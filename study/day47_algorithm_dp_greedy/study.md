# 必须会背会写

- 动态规划五要素是状态定义、转移方程、初始化、遍历顺序和答案位置；状态必须覆盖子问题所需的全部信息
- 0/1 背包一维压缩时容量逆序，完全背包通常正序；不可达状态用负无穷或显式标记，不能误当零
- 区间 DP 常按区间长度从短到长；回溯需要选择、撤销和剪枝；贪心需要选择性质或交换论证
- Java DP 数组的 `int`/`long` 选择取决于最大和、乘法和输入范围；状态转移前要处理不可达值
- 一维 0/1 背包代码骨架是：
  ```java
  for (int i = 0; i < n; i++)
      for (int c = capacity; c >= weight[i]; c--)
          dp[c] = Math.max(dp[c], dp[c - weight[i]] + value[i]);
  ```
- 外部源码索引（会背会写）：[DP INDEX](../../../AI_Infra/Phases_book/algorithm/6_动态规划_回溯_贪心与位运算/INDEX.md) 的状态/转移题面；[Java Arrays](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Arrays.html) 的初始化与填充

# 必须理解

- 遍历方向决定元素是否重复使用；“样例通过”不能证明贪心正确，反例或交换论证才是正确性依据
- 外部源码索引（必须理解）：[CP-Algorithms DP intro](https://cp-algorithms.com/dynamic_programming/intro-to-dp.html) 的状态压缩与复杂度；[Greedy algorithms](https://cp-algorithms.com/greedy/intro-to-greedy.html) 的证明方法
- 题面索引：[动态规划/回溯/贪心 INDEX](../../../AI_Infra/Phases_book/algorithm/6_动态规划_回溯_贪心与位运算/INDEX.md)；官方：[DP](https://cp-algorithms.com/dynamic_programming/intro-to-dp.html)
