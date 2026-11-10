package algo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

/**
 * 快排的 JUnit 5 用例。
 * <p>
 * 注意：这里<b>不要</b>加 @SpringBootTest —— 加了就会启动整个 Spring 容器（Nacos/Redis/MySQL 全要连），
 * 纯算法用普通 @Test 即可，秒级跑完。
 * <p>
 * 跑法：类名或单个方法左边点绿色三角；或命令行 mvn -pl tj-aigc test -Dtest=SortsTest
 */
class SortsTest {

    /** 通用校验：把自己的排序结果跟 JDK 的 Arrays.sort 比对 */
    private void assertSortEqualsJdk(int[] input) {
        int[] expected = input.clone();
        int[] actual = input.clone();

        Arrays.sort(expected);
        Sorts.quickSort(actual);

        assertArrayEquals(expected, actual, () -> "输入：" + Arrays.toString(input));
    }

    @Test
    @DisplayName("边界用例：空数组 / 单元素 / 双元素")
    void shouldHandleEdgeCases() {
        assertSortEqualsJdk(new int[]{});
        assertSortEqualsJdk(new int[]{1});
        assertSortEqualsJdk(new int[]{2, 1});
        assertSortEqualsJdk(new int[]{1, 2});
    }

    @Test
    @DisplayName("特殊分布：已有序 / 完全逆序 / 全部相等 / 含负数")
    void shouldHandleSpecialDistributions() {
        assertSortEqualsJdk(new int[]{1, 2, 3, 4, 5});
        assertSortEqualsJdk(new int[]{5, 4, 3, 2, 1});
        assertSortEqualsJdk(new int[]{7, 7, 7, 7, 7, 7});
        assertSortEqualsJdk(new int[]{0, -3, 9, -8, 2, -3, Integer.MIN_VALUE, Integer.MAX_VALUE});
    }

    @Test
    @DisplayName("随机对拍 2000 组")
    void shouldMatchJdkOnRandomArrays() {
        Random random = new Random(42);
        for (int round = 0; round < 2000; round++) {
            int[] nums = random.ints(random.nextInt(200), -500, 500).toArray();
            assertSortEqualsJdk(nums);
        }
    }

    @Test
    @DisplayName("10 万随机数 2 秒内排完")
    void shouldSortLargeArrayQuickly() {
        int[] nums = new Random(7).ints(100_000).toArray();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> Sorts.quickSort(nums));
        assertTrue(isSorted(nums), "排序结果应为升序");
    }

    /** 参数化用例演示：把一批固定输入写成数据源 */
    @ParameterizedTest(name = "第 {index} 组：{0}")
    @MethodSource("fixedCases")
    @DisplayName("固定用例集")
    void shouldPassFixedCases(int[] input) {
        assertSortEqualsJdk(input);
    }

    static Stream<int[]> fixedCases() {
        return Stream.of(
                new int[]{3, 1, 3, 2, 1, 3},
                new int[]{-1, -100, 50, 0, 7},
                new int[]{9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
        );
    }

    private static boolean isSorted(int[] nums) {
        for (int i = 1; i < nums.length; i++) {
            if (nums[i - 1] > nums[i]) {
                return false;
            }
        }
        return true;
    }
}
