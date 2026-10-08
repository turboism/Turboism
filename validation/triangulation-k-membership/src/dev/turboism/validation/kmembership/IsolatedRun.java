package dev.turboism.validation.kmembership;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import dev.turboism.validation.kmembership.Fixture.EdgeJ;
import dev.turboism.validation.kmembership.Fixture.EdgeK;
import dev.turboism.validation.kmembership.Fixture.TriL;
import dev.turboism.validation.kmembership.Fixture.TriPoint;

/**
 * Missing-helper isolation driver: run with classes-main+stdlib+asm ONLY
 * (no classes-helper on classpath). The woven class must still LOAD and every
 * query must delegate to the original linear path — proven via
 * EdgeK.ORIGINAL_QUERIES. This class must not reference Helper anywhere.
 */
public final class IsolatedRun {
    private IsolatedRun() {}

    static TriPoint pt(int i) { return new TriPoint(i, i, i); }
    static EdgeJ edge(int a, int b) { return new EdgeJ(pt(a), pt(b)); }

    public static void main(String[] args) throws Exception {
        String name = "dev.turboism.validation.kmembership.WeaveTarget";
        byte[] woven;
        try (java.io.InputStream is = IsolatedRun.class.getResourceAsStream(
                "/dev/turboism/validation/kmembership/WeaveTarget.class")) {
            woven = Weave.weave(is.readAllBytes());
        }
        ClassLoader cl = new ClassLoader(IsolatedRun.class.getClassLoader()) {
            @Override public Class<?> loadClass(String n, boolean r)
                    throws ClassNotFoundException {
                synchronized (getClassLoadingLock(n)) {
                    Class<?> c = findLoadedClass(n);
                    if (c == null && n.equals(name))
                        c = defineClass(n, woven, 0, woven.length);
                    if (c == null) c = super.loadClass(n, false);
                    if (r) resolveClass(c);
                    return c;
                }
            }
        };
        Class<?> wc = Class.forName(name, true, cl);

        LinkedHashSet<TriL> in = new LinkedHashSet<>();
        in.add(new TriL(edge(1,2), edge(2,3), edge(1,3)));
        in.add(new TriL(edge(3,2), edge(2,4), edge(3,4)));

        // class must verify/load despite Helper being absent (-Xverify:all run)
        Object tl = wc.getDeclaredConstructor(LinkedHashSet.class).newInstance(in);
        EdgeK k = (EdgeK) wc.getMethod("b").invoke(tl);
        List<EdgeJ> list = new ArrayList<>(k.a());

        // expected: 6 query positions, all delegated; 5 unique undirected edges
        // {(1,2),(2,3),(1,3),(2,4),(3,4)} — (3,2) is the reverse of (2,3)
        int orig = EdgeK.ORIGINAL_QUERIES.get();
        if (orig != 6) { System.out.println("ISOLATED FAIL originalQueries=" + orig); System.exit(1); }
        if (list.size() != 5) { System.out.println("ISOLATED FAIL size=" + list.size()); System.exit(1); }
        // second call: again fully delegated, fresh local null path
        EdgeK.ORIGINAL_QUERIES.set(0);
        wc.getMethod("b").invoke(wc.getDeclaredConstructor(LinkedHashSet.class).newInstance(in));
        if (EdgeK.ORIGINAL_QUERIES.get() != 6) { System.out.println("ISOLATED FAIL second-call"); System.exit(1); }
        System.out.println("KBUILD_ISOLATED PASS originalQueriesPerCall=6 listSize=5 helperAbsent=true");
    }
}
