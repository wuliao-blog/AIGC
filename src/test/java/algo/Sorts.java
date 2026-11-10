package algo;

/**
 * 算法实现区：每练一个新算法，就在这个类里加一个 static 方法。
 * <p>
 * 好处是「实现」和「用例」分开，用例可以复用一批通用断言方法，
 * 也能被 mvn test 直接跑到。
 */
public class Sorts {

    private Sorts() {
    }

    /**
     * 快速排序（升序），原地排序，平均 O(n log n)，最差 O(n^2)。
     */
    public static void quickSort(int[] nums) {
        if (nums == null || nums.length < 2) {
            return;
        }
        quickSort(nums, 0, nums.length - 1);
    }

    private static void quickSort(int[] nums, int left, int right) {
        if (left >= right) {
            return;
        }
        int pivotIndex = partition(nums, left, right);
        quickSort(nums, left, pivotIndex - 1);
        quickSort(nums, pivotIndex + 1, right);
    }

    private static int partition(int[] nums, int left, int right) {
        int pivot = nums[right];
        int i = left;
        for (int j = left; j < right; j++) {
            if (nums[j] < pivot) {
                swap(nums, i++, j);
            }
        }
        swap(nums, i, right);
        return i;
    }

    private static void swap(int[] nums, int i, int j) {
        if (i == j) {
            return;
        }
        int tmp = nums[i];
        nums[i] = nums[j];
        nums[j] = tmp;
    }
}
