package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocVersion;
import dev.turboism.sdk.cubism.core.OwnedCanvasInfo;
import dev.turboism.sdk.cubism.core.OwnedDeformer;
import dev.turboism.sdk.cubism.core.OwnedDrawable;
import dev.turboism.sdk.cubism.core.OwnedGlue;
import dev.turboism.sdk.cubism.core.OwnedMoc;
import dev.turboism.sdk.cubism.core.OwnedModel;
import dev.turboism.sdk.cubism.core.OwnedParameter;
import dev.turboism.sdk.cubism.core.OwnedPart;
import java.util.List;

/** Retained owned handles recheck the plugin scope before native reads or evaluation. */
final class PermissionCheckedOwnedMoc implements OwnedMoc {
    private final CubismFacadeImpl facade;
    private final OwnedMoc delegate;

    PermissionCheckedOwnedMoc(final CubismFacadeImpl facade, final OwnedMoc delegate) {
        this.facade = facade;
        this.delegate = delegate;
    }

    @Override
    public MocVersion version() {
        check("version");
        return delegate.version();
    }

    @Override
    public MocConsistency consistency() {
        check("consistency");
        return delegate.consistency();
    }

    @Override
    public long nativeHandle() {
        check("nativeHandle");
        return delegate.nativeHandle();
    }

    @Override
    public OwnedModel instantiateModel() {
        check("instantiateModel");
        return new Model(facade, delegate.instantiateModel());
    }

    /** Releasing a plugin-owned allocation remains allowed after its scope is deactivated. */
    @Override
    public void close() {
        delegate.close();
    }

    private void check(final String method) {
        facade.requireModelRead("coreRuntime.ownedMoc." + method);
    }

    private record Model(CubismFacadeImpl facade, OwnedModel delegate) implements OwnedModel {
        @Override
        public long nativeHandle() {
            check("nativeHandle");
            return delegate.nativeHandle();
        }

        @Override
        public OwnedCanvasInfo canvasInfo() {
            check("canvasInfo");
            return delegate.canvasInfo();
        }

        @Override
        public List<OwnedParameter> parameters() {
            check("parameters");
            return delegate.parameters();
        }

        @Override
        public List<OwnedPart> parts() {
            check("parts");
            return delegate.parts();
        }

        @Override
        public List<OwnedDrawable> drawables() {
            check("drawables");
            return delegate.drawables();
        }

        @Override
        public List<OwnedGlue> glues() {
            check("glues");
            return delegate.glues();
        }

        @Override
        public List<OwnedDeformer> deformers() {
            check("deformers");
            return delegate.deformers();
        }

        @Override
        public void update() {
            check("update");
            delegate.update();
        }

        @Override
        public void close() {
            delegate.close();
        }

        private void check(final String method) {
            facade.requireModelRead("coreRuntime.ownedModel." + method);
        }
    }
}
