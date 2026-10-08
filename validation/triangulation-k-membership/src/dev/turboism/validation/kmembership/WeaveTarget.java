package dev.turboism.validation.kmembership;

import java.util.LinkedHashSet;

import dev.turboism.validation.kmembership.Fixture.EdgeJ;
import dev.turboism.validation.kmembership.Fixture.EdgeK;
import dev.turboism.validation.kmembership.Fixture.TriL;

/**
 * Plain (observer-free) build-window target class. Its bytecode is the weaving
 * input: the same loop shape as the real TriangleList.b() — fresh k, three
 * getters first, then three (k.a(j,false) query → conditional k.a(j) append).
 * The woven form of this class is exercised through a child classloader.
 */
public class WeaveTarget {
    private final LinkedHashSet<TriL> b;
    public WeaveTarget(LinkedHashSet<TriL> b) { this.b = b; }
    public EdgeK b() {
        EdgeK k = new EdgeK();
        for (TriL tri : b) {
            EdgeJ j4 = tri.d();
            EdgeJ j5 = tri.e();
            EdgeJ j6 = tri.f();
            if (!k.a(j4, false)) k.a(j4);
            if (!k.a(j5, false)) k.a(j5);
            if (!k.a(j6, false)) k.a(j6);
        }
        return k;
    }
}
