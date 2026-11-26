package nie.translator.rtranslator.tools.nn;

import java.util.ArrayList;
import java.util.Random;

/**
 * Utils.topK (one pass, used by the beam search) gives exactly what k successive calls of
 * getIndexOfLargest(array, alreadyChosen) gave: same indexes in the same order, ties to the lowest index,
 * NaN / -inf / -Float.MAX_VALUE never chosen, index 0 for unfilled slots. Run: java ...tools.nn.UtilsTest
 */
public class UtilsTest {
    public static void main(String[] args) {
        Random rnd = new Random(7);
        int failures = 0;
        for (int t = 0; t < 20000; t++) {
            int n = 1 + rnd.nextInt(40), k = 1 + rnd.nextInt(6);
            float[] a = new float[n];
            for (int i = 0; i < n; i++) {
                int kind = rnd.nextInt(10);
                a[i] = kind == 0 ? Float.NaN : kind == 1 ? Float.NEGATIVE_INFINITY : kind == 2 ? -Float.MAX_VALUE
                        : kind < 5 ? (float) rnd.nextInt(3) : (float) rnd.nextGaussian();   // many ties
            }
            ArrayList<Integer> chosen = new ArrayList<>();
            int[] expected = new int[k];
            for (int j = 0; j < k; j++) { expected[j] = Utils.getIndexOfLargest(a, chosen); chosen.add(expected[j]); }
            int[] got = new int[k];
            Utils.topK(a, k, got);
            for (int j = 0; j < k; j++) if (got[j] != expected[j]) { failures++; break; }
        }
        // logSumExpFast with the known maximum == without it
        float[] v = {0.5f, -3f, 2f, 1.25f, -40f};
        if (Math.abs(Utils.logSumExpFast(v, 2f) - Utils.logSumExpFast(v)) > 1e-9) failures++;
        System.out.println(failures == 0 ? "ALL PASSED (topK == repeated getIndexOfLargest on 20000 arrays)" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
