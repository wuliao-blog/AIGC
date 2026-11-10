import java.util.Arrays;
import java.util.Random;

/**
 * 算法练习沙箱 —— 最快的跑法：直接 Run main()，不用启动 Spring Boot。
 * <p>
 * 为什么不启动项目：tj-aigc 依赖 Nacos / Redis / MySQL，启动一次十几秒还可能连不上，
 * 而算法题只需要 JVM。IDEA 里点 main 左边的绿三角即可，单元测试同理。
 * <p>
 * 日常练习套路：只改「实现区」，运行后看控制台 PASS / FAIL 和耗时。
 */
public class test {

        public static void main(String[] args) {
            int[] arr = {5, 2, 9, 3, 7, 6, 1, 8};
            quickSort(arr, 0, arr.length - 1);
            quickSort1(arr, 0, arr.length -1 );
            for (int num : arr) {
                System.out.print(num + " ");
            }
        }

    private static void quickSort1(int[] arr, int left, int right) {
            if (left < right) {
                return;
            }
            //分区, 进行排序，并返回基准最后的位置
        int pivotIndex = partition1(arr, left, right);
            // 递归排左边
            quickSort1(arr, 0, left - 1);
            // 递归排右边
            quickSort1(arr, left + 1,  right);
    }

    private static int partition1(int[] arr, int left, int right) {


            return 0;
    }


    /**
         * @param arr 数组
         * @param left 左边界下标
         * @param right 右边界下标
         */
        public static void quickSort(int[] arr, int left, int right) {
            // 递归终止条件：区间只有0或1个元素，不用排
            if (left >= right) {
                return;
            }
            // 分区，返回基准值最后的位置
            int pivotIndex = partition(arr, left, right);
            // 递归排左边
            quickSort(arr, left, pivotIndex - 1);
            // 递归排右边
            quickSort(arr, pivotIndex + 1, right);
        }

        // 分区函数：返回pivot的正确下标
        private static int partition(int[] arr, int left, int right) {
            int pivot = arr[left]; // 选最左边当基准
            int i = left;
            int j = right;

            while (i < j) {
                // 右指针往左走，找小于 pivot 的数
                while (i < j && arr[j] >= pivot) {
                    j--;
                }
                // 左指针往右走，找大于 pivot 的数
                while (i < j && arr[i] <= pivot) {
                    i++;
                }
                // i<j，交换两个数
                if (i < j) {
                    int temp = arr[i];
                    arr[i] = arr[j];
                    arr[j] = temp;
                }
            }
            // i == j，把基准换到相遇点
            arr[left] = arr[i];
            arr[i] = pivot;
            return i;
        }


}
