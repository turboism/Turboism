package dev.turboism.mapping.verification;

/**
 * The one host entry class every reviewed Cubism Editor artifact declares.
 *
 * <p>{@code CEAppCtrl} is the application controller: the agent locates it to bind the host loader,
 * the release detector uses it as the archive anchor, and the lifecycle and modeling adapters name
 * it as the owner of their seams. The value lives here so a composition layer can name the default
 * host class without restating a host-package literal.</p>
 */
public final class CubismHostEntry {

    /** Dotted binary name of the Editor application controller. */
    public static final String CLASS_NAME = "com.live2d.cubism.CEAppCtrl";

    private CubismHostEntry() {}
}
