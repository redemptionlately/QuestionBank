# Day45 Algorithm: Array & Hash · 题目与标准解答（Solutions）

> Java 21 可运行示例；每道题给出循环不变量、复杂度与边界。

## Current

### Q1. 按当天索引定位并口述 I/O 边界（方法论题）。
复杂度分析必须同时写：时间、额外空间、输入范围、递归深度、整数溢出边界，缺一不可。

---

### Q2. 双指针：有序数组两数之和（两指针单调移动）。
```java
public int[] twoSumSorted(int[] a, int target) {
    int l = 0, r = a.length - 1;
    while (l < r) {                       // 不变量：若答案存在，必在 [l,r] 内
        int s = a[l] + a[r];
        if (s == target) return new int[]{l, r};
        if (s < target) l++; else r--;    // 和太小→左指针右移，太大→右指针左移
    }
    return new int[]{-1, -1};
}
```
时间 O(n)、空间 O(1)。边界：空数组/单元素直接无解；两数相加用 `long` 防 int 溢出。

---

### Q3. 滑动窗口核心循环（覆盖重复值、空窗口、负数）。
**最长无重复字符子串**：
```java
public int longestUnique(String s) {
    Map<Character, Integer> last = new HashMap<>();
    int left = 0, ans = 0;
    for (int right = 0; right < s.length(); right++) {   // 不变量：[left,right] 始终无重复
        char c = s.charAt(right);
        if (last.containsKey(c))
            left = Math.max(left, last.get(c) + 1);      // 重复值：left 只能前进不能后退
        last.put(c, right);
        ans = Math.max(ans, right - left + 1);           // 空窗口时 ans 保持 0
    }
    return ans;
}
```
通用骨架：`right 扩展 → while 窗口不合法则 left 收缩 → 更新答案`。
边界注意：① 重复值用 `Math.max` 避免 left 回退；② 空串/窗口为空时答案 0；③ 负数不影响“计数/去重型”窗口，但“和≥target 收缩型”窗口在含负数时单调性被破坏，不能机械套收缩模板，需前缀和等方法。时间 O(n)（每元素进出窗口各一次），空间 O(k)。

---

### Q4. 前缀和 + HashMap 区间计数（和为 K 的子数组个数）。
```java
public int subarraySum(int[] nums, int k) {
    Map<Long, Integer> prefixCount = new HashMap<>();
    prefixCount.put(0L, 1);                  // key=前缀和，value=出现次数；初始和0出现1次
    long sum = 0; int ans = 0;
    for (int x : nums) {
        sum += x;
        ans += prefixCount.getOrDefault(sum - k, 0); // 找此前前缀和 = sum-k 的次数
        prefixCount.merge(sum, 1, Integer::sum);
    }
    return ans;
}
```
**key 的含义**：`prefix[i]=a[0..i-1]` 之和，区间 [j,i) 和 = prefix[i]-prefix[j]，要其等于 k 即需 prefix[j]=prefix[i]-k，用 map 把“两次访问”降为 O(1)。时间 O(n)、空间 O(n)。用 long 累加防溢出；含负数/零仍成立（不依赖单调性）。

---

### Q5. HashMap 计数/去重/两数和模式与复杂度。
- 计数：`map.merge(x,1,Integer::sum)`；
- 去重：`Set.add` 返回 false 即重复；
- 两数和（无序）：遍历到 x 时查 map 中有无 target-x；
- 平均 O(1) 查找依赖良好 hash 分布（最坏哈希全冲突退化 O(n)）；遍历 HashMap 无序，需要有序结果用 TreeMap 或排序。

---

## External

### E1. 第 1/3/7 天复习与变式。
按间隔重复：当天写通 → 第 1 天默写不变量 → 第 3 天换数据类型/问法（最长→最短、计数→是否存在）→ 第 7 天限时复现。变式示例：最长无重复子串 → “至多包含 K 个不同字符的最长子串”（把判断条件换成 `map.size()>K` 收缩）。

### E2. 边界测试清单。
空数组、单元素、全相同元素、全负数、目标不存在、和恰好等于 int 边界（用 long）、窗口合法条件恒真/恒假。每题至少补这几类用例再认为正确。
