# 必须会背会写

- 双指针依靠左右指针单调移动降低嵌套循环；滑动窗口维护 `[left,right]` 不变量；前缀和 `prefix[i+1]=prefix[i]+a[i]` 将区间和转为两次访问
- HashMap 平均 O(1) 查找依赖 hash 分布；常见模式是计数、去重、两数和、前缀和映射和窗口频次
- 复杂度分析同时写时间、额外空间、输入范围、递归深度和整数溢出边界
- Java 写法常用 `Map<Integer,Integer> count = new HashMap<>(); count.merge(x, 1, Integer::sum);`
- 滑动窗口代码骨架是：
  ```java
  int left = 0;
  for (int right = 0; right < a.length; right++) {
      add(a[right]);
      while (!valid()) remove(a[left++]);
      answer = Math.max(answer, right - left + 1);
  }
  ```
- 外部源码索引（会背会写）：[Java HashMap API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/HashMap.html) 的 `get/put/merge`；[数组/窗口 INDEX](../../../../AI_Infra/Phases_book/algorithm/1_数组_窗口_前缀和与选择/INDEX.md) 的题面与变式

# 必须理解

- 滑窗正确性依赖窗口不变量；重复值、空窗口、负数和边界决定窗口能否单调收缩，不能机械套模板
- 外部源码索引（必须理解）：[CP-Algorithms prefix sums](https://cp-algorithms.com/data_structures/prefix_sum.html) 与 [two pointers](https://cp-algorithms.com/others/two_pointers.html) 的不变量证明
- 题面入口是 [数组/窗口/前缀和 INDEX](../../../../AI_Infra/Phases_book/algorithm/1_数组_窗口_前缀和与选择/INDEX.md)，总索引是 [algorithm/INDEX.md](../../../../AI_Infra/Phases_book/algorithm/INDEX.md)；官方：[LeetCode](https://leetcode.com/problemset/)、[CP-Algorithms](https://cp-algorithms.com/)
