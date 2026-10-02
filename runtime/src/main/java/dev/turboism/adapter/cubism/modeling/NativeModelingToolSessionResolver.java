package dev.turboism.adapter.cubism.modeling;

import static dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.*;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Objects;
import java.util.Optional;
import javax.swing.JComponent;

/** Resolves exact document/mode/model/view/component/camera identity without opening an edit session. */
public final class NativeModelingToolSessionResolver {
    private final VerifiedMemberResolver resolver;

    public NativeModelingToolSessionResolver(VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        if (!ModelingSelectionSelectorContract.authorizes(resolver)) {
            throw new IllegalArgumentException("ordinary modeling brush selectors are not authorized");
        }
    }

    /** Resolves ordinary modeling mode without borrowing an edit already in progress. */
    public Optional<Identity> resolve() {
        return resolve(false);
    }

    private Optional<Identity> resolve(boolean allowEditing) {
        if (!ModelingSelectionSelectorContract.authorizes(resolver)) return Optional.empty();
        final Object app = resolver.invokeStatic(APP);
        final Object document = resolver.invoke(DOCUMENT, app);
        if (!resolver.isInstance(DOCUMENT_CLASS, document)) return Optional.empty();
        final Object mode = resolver.invoke(CURRENT_MODE, document);
        if (!resolver.isInstance(MAIN_MODE_CLASS, mode)
                || (!allowEditing && Boolean.TRUE.equals(resolver.invoke(MODE_EDITING, mode)))) return Optional.empty();
        final Object view = resolver.invoke(VIEW, document);
        if (!resolver.isInstance(VIEW_CLASS, view) || resolver.invoke(CURRENT_VIEW, app) != view)
            return Optional.empty();
        final Object pack = resolver.invoke(PACK, view);
        final Object mainView = resolver.invoke(MAIN_VIEW, pack);
        final Object rawComponent = resolver.invoke(COMPONENT, mainView);
        final Object camera = resolver.invoke(CAMERA, view);
        final Object model = resolver.invoke(MODEL, view);
        final Object selector = resolver.invoke(MAIN_SELECTOR, mode);
        if (!(rawComponent instanceof JComponent component) || camera == null || model == null || selector == null) {
            return Optional.empty();
        }
        return Optional.of(new Identity(app, document, mode, model, view, pack, component, camera, selector));
    }

    /** Compares every target identity, allowing this adapter's own brief selection transaction. */
    public boolean isCurrent(Identity expected) {
        return expected != null && resolve(true).filter(expected::same).isPresent();
    }

    VerifiedMemberResolver resolver() {
        return resolver;
    }

    /** Immutable native target identities used only inside verified runtime adapters. */
    public record Identity(
            Object app,
            Object document,
            Object mode,
            Object model,
            Object view,
            Object pack,
            JComponent component,
            Object camera,
            Object selector) {
        /** Uses reference identity for every native owner, view, camera and component. */
        public boolean same(Identity other) {
            return other != null
                    && app == other.app
                    && document == other.document
                    && mode == other.mode
                    && model == other.model
                    && view == other.view
                    && pack == other.pack
                    && component == other.component
                    && camera == other.camera
                    && selector == other.selector;
        }
    }
}
