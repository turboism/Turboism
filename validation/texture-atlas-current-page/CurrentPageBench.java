import dev.turboism.plugin.atlasmaxrectsbssf.layout.CurrentPageTextureAtlasPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Pure production planner timing, NOT a Cubism native or UI benchmark. */
public final class CurrentPageBench {
    private static volatile Object sink;

    public static void main(String[] args) {
        int count = Integer.parseInt(args[0]);
        int side = Integer.parseInt(args[1]);
        boolean parallel = Boolean.parseBoolean(args[2]);
        double requestedScale = Double.parseDouble(args[3]);
        boolean rotate = Boolean.parseBoolean(args[4]);
        int margin = Integer.parseInt(args[5]);
        Random random = new Random(51);
        var items = new ArrayList<TextureAtlasLayoutItem>();
        for (int i = 0; i < count; i++) {
            items.add(new TextureAtlasLayoutItem(String.format(Locale.ROOT, "item-%05d", i),
                16 + random.nextInt(81), 16 + random.nextInt(81)));
        }
        var constraints = TextureAtlasLayoutConstraints.currentPage(side, side, margin, rotate, requestedScale);
        var planner = new CurrentPageTextureAtlasPlanner();
        double[] samples = new double[7];
        for (int iteration = 0; iteration < 10; iteration++) {
            long start = System.nanoTime();
            var plan = planner.plan(items, constraints, parallel);
            double elapsed = (System.nanoTime() - start) / 1e6;
            sink = plan;
            if (plan.pageCount() != 1 || plan.placements().size() > count) throw new AssertionError("scope");
            if (requestedScale > 0 && plan.scale() != requestedScale) throw new AssertionError("fixed scale");
            if (requestedScale == 0 && plan.scale() > 1) throw new AssertionError("automatic enlargement");
            if (iteration >= 3) samples[iteration - 3] = elapsed;
            System.out.printf(Locale.ROOT,
                "n=%d side=%d parallel=%s requested=%s rotate=%s margin=%d iteration=%d ms=%.3f placed=%d overflow=%d scale=%.9f%n",
                count, side, parallel, requestedScale, rotate, margin, iteration, elapsed,
                plan.placements().size(), count - plan.placements().size(), plan.scale());
        }
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "warm_median_ms=%.3f warm_max_ms=%.3f%n", samples[3], samples[6]);
    }
}
