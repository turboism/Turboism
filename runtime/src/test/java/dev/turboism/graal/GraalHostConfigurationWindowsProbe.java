package dev.turboism.graal;

import java.nio.file.Path;

public final class GraalHostConfigurationWindowsProbe {

    private GraalHostConfigurationWindowsProbe() {
    }

    public static void main(final String[] args) {
        final GraalHostConfiguration configuration = GraalHostConfiguration.resolve(Path.of(args[0]));
        System.out.print(configuration.classpath());
    }
}
