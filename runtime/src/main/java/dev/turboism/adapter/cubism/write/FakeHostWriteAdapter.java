package dev.turboism.adapter.cubism.write;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.sdk.cubism.boundingbox.BoundingBoxWriteCommand;
import dev.turboism.sdk.cubism.deformer.DeformerWriteCommand;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.mesh.MeshWriteCommand;
import dev.turboism.sdk.cubism.mesh.MirrorWritebackCommand;
import dev.turboism.sdk.cubism.psd.PsdBindingWriteCommand;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;
import dev.turboism.sdk.cubism.write.WriteCanvasCommand;
import dev.turboism.sdk.cubism.write.WriteClipMaskCommand;
import dev.turboism.sdk.cubism.write.WriteModelObjectCommand;
import dev.turboism.sdk.cubism.write.WriteParameterCommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class FakeHostWriteAdapter implements HostWriteAdapter, HostSnapshotSource {

    private static final int UNKNOWN_DOCUMENT = 1301;
    private static final int UNKNOWN_MODEL = 1302;
    private static final int UNKNOWN_PARAMETER = 1303;
    private static final int INVALID_COMMAND = 1304;

    private final Map<String, FakeDocument> documents = new LinkedHashMap<>();
    private String activeDocumentId;
    private boolean hostPresent = true;
    private long version;

    public synchronized void addDocument(final String documentId, final String documentName, final FakeModel model) {
        final FakeDocument document = new FakeDocument(documentId, documentName, model.copy(), List.of(), 0, 0);
        documents.put(document.id(), document);
        activeDocumentId = document.id();
        version++;
    }

    public synchronized double parameterValue(final String modelId, final String parameterId) {
        final FakeDocument document = activeFakeDocument();
        if (document == null || !document.model().id().equals(modelId)) {
            throw new IllegalArgumentException("Unknown model " + modelId);
        }
        final FakeParameter parameter = document.model().parameters().get(parameterId);
        if (parameter == null) {
            throw new IllegalArgumentException("Unknown parameter " + parameterId);
        }
        return parameter.value();
    }

    public synchronized List<String> appliedCommandIds() {
        FakeDocument document = activeFakeDocument();
        return document == null ? List.of() : document.operations();
    }

    public synchronized int canvasWidth() {
        FakeDocument document = activeFakeDocument();
        return document == null ? 0 : document.canvasWidth();
    }

    public synchronized int canvasHeight() {
        FakeDocument document = activeFakeDocument();
        return document == null ? 0 : document.canvasHeight();
    }

    @Override
    public synchronized HostSnapshot capture(final DocumentId documentId) throws TransactionException {
        final FakeDocument document = document(documentId);
        return new FakeHostSnapshot(documentId, document.copy(), version);
    }

    @Override
    public synchronized void apply(final DocumentId documentId, final List<CubismWriteCommand> commands)
        throws TransactionException {
        final FakeDocument document = document(documentId);
        for (CubismWriteCommand command : commands) {
            applyCommand(document, command);
        }
        version++;
    }

    @Override
    public synchronized void restore(final HostSnapshot snapshot) throws TransactionException {
        final FakeDocument document = documentFrom(snapshot);
        documents.put(snapshot.documentId().id(), document.copy());
        activeDocumentId = snapshot.documentId().id();
        version++;
    }

    @Override
    public synchronized long version() {
        return version;
    }

    @Override
    public synchronized Optional<HostProject> activeProject() {
        return Optional.of(new HostProject("fake-project", "Fake Project", Optional.of(Path.of("projects/fake")),
            documents.values().stream().map(this::hostDocument).toList()));
    }

    @Override
    public synchronized Optional<HostDocument> activeDocument() {
        if (activeDocumentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(documents.get(activeDocumentId)).map(this::hostDocument);
    }

    @Override
    public synchronized Optional<HostModel> activeModel() {
        return activeDocument().flatMap(HostDocument::model);
    }

    @Override
    public HostSelection selection() {
        return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Override
    public synchronized boolean isHostPresent() {
        return hostPresent;
    }

    @Override
    public synchronized long invalidationToken() {
        return version;
    }

    private void applyCommand(final FakeDocument document, final CubismWriteCommand command) throws TransactionException {
        if (command instanceof WriteParameterCommand parameterCommand) {
            applyParameterCommand(document, parameterCommand);
        } else if (command instanceof WriteCanvasCommand canvasCommand) {
            validateModel(document, canvasCommand.modelId(), canvasCommand.commandId());
            document.addOperation(canvasCommand.commandId());
        } else if (command instanceof WriteModelObjectCommand modelObjectCommand) {
            validateModel(document, modelObjectCommand.modelId(), modelObjectCommand.commandId());
            document.addOperation(modelObjectCommand.commandId());
        } else if (command instanceof WriteClipMaskCommand clipMaskCommand) {
            if (clipMaskCommand.clippedMeshIds().isEmpty()) {
                throw error(clipMaskCommand.commandId(), INVALID_COMMAND, "Clip-mask command requires at least one clipped mesh");
            }
            document.addOperation(clipMaskCommand.commandId());
        } else if (command instanceof MeshWriteCommand meshCommand) {
            validateModel(document, meshCommand.modelId(), meshCommand.commandId());
            document.addOperation(meshCommand.commandId());
        } else if (command instanceof DeformerWriteCommand deformerCommand) {
            validateModel(document, deformerCommand.modelId(), deformerCommand.commandId());
            document.addOperation(deformerCommand.commandId());
        } else if (command instanceof MirrorWritebackCommand mirrorCommand) {
            validateModel(document, mirrorCommand.modelId(), mirrorCommand.commandId());
            document.addOperation(mirrorCommand.commandId());
        } else if (command instanceof PsdBindingWriteCommand psdCommand) {
            validateModel(document, psdCommand.modelId(), psdCommand.commandId());
            document.addOperation(psdCommand.commandId());
        } else if (command instanceof BoundingBoxWriteCommand boundingBoxCommand) {
            validateModel(document, boundingBoxCommand.modelId(), boundingBoxCommand.commandId());
            document.addOperation(boundingBoxCommand.commandId());
        } else {
            throw error(command.commandId(), INVALID_COMMAND, "Unknown write command type " + command.getClass().getName());
        }
    }

    private void applyParameterCommand(final FakeDocument document, final WriteParameterCommand command) throws TransactionException {
        validateModel(document, command.modelId(), command.commandId());
        final FakeParameter parameter = document.model().parameters().get(command.parameterId().value());
        if (parameter == null) {
            throw error(command.commandId(), UNKNOWN_PARAMETER, "Unknown parameter " + command.parameterId().value());
        }
        parameter.setValue(command.value());
        document.addOperation(command.commandId());
    }

    private void validateModel(final FakeDocument document, final ModelId modelId, final String commandId) throws TransactionException {
        if (!document.model().id().equals(modelId.value())) {
            throw error(commandId, UNKNOWN_MODEL, "Unknown model " + modelId.value());
        }
    }

    private FakeDocument document(final DocumentId documentId) throws TransactionException {
        final FakeDocument document = documents.get(documentId.id());
        if (document == null) {
            throw error(documentId.id(), UNKNOWN_DOCUMENT, "Unknown document " + documentId.id());
        }
        return document;
    }

    private FakeDocument activeFakeDocument() {
        return activeDocumentId == null ? null : documents.get(activeDocumentId);
    }

    private FakeDocument documentFrom(final HostSnapshot snapshot) throws TransactionException {
        if (snapshot instanceof FakeHostSnapshot fakeSnapshot) {
            final FakeDocument document = fakeSnapshot.document();
            return document;
        }
        throw error(snapshot.documentId().id(), UNKNOWN_DOCUMENT, "Invalid fake snapshot");
    }

    private HostDocument hostDocument(final FakeDocument document) {
        return new HostDocument(
            document.id(),
            document.name(),
            "documents/" + document.id() + ".cdi3.json",
            Optional.of(Path.of("documents", document.id() + ".cdi3.json")),
            Optional.of(hostModel(document.model()))
        );
    }

    private HostModel hostModel(final FakeModel model) {
        return new HostModel(model.id(), model.name(), model.parameters().values().stream().map(this::hostParameter).toList(), List.of(), List.of());
    }

    private HostParameter hostParameter(final FakeParameter parameter) {
        return new HostParameter(parameter.id(), parameter.name(), parameter.value(), 0.0, -1.0, 1.0, true, true);
    }

    private static TransactionException error(final String id, final int code, final String message) {
        return new TransactionException(id, code, "ERROR", message);
    }

    public record FakeModel(String id, String name, Map<String, FakeParameter> parameters) {
        public FakeModel(final ModelId id, final String name, final List<FakeParameter> parameters) {
            this(id.value(), name, keyed(parameters));
        }

        private FakeModel copy() {
            return new FakeModel(id, name, copyParameters(parameters));
        }
    }

    public static final class FakeParameter {
        private final String id;
        private final String name;
        private double value;

        public FakeParameter(final String id, final String name, final double value) {
            this.id = Objects.requireNonNull(id, "id");
            this.name = Objects.requireNonNull(name, "name");
            this.value = value;
        }

        String id() {
            return id;
        }

        String name() {
            return name;
        }

        double value() {
            return value;
        }

        void setValue(final double value) {
            this.value = value;
        }

        FakeParameter copy() {
            return new FakeParameter(id, name, value);
        }
    }

    private record FakeDocument(String id, String name, FakeModel model, List<String> operations, int canvasWidth, int canvasHeight) {
        private FakeDocument {
            operations = new ArrayList<>(operations);
        }

        private FakeDocument copy() {
            return new FakeDocument(id, name, model.copy(), operations, canvasWidth, canvasHeight);
        }

        private void addOperation(String commandId) {
            operations.add(commandId);
        }

        private FakeDocument withOperation(String commandId) {
            FakeDocument copy = copy();
            copy.addOperation(commandId);
            return copy;
        }

        private FakeDocument withCanvas(int width, int height) {
            return new FakeDocument(id, name, model, operations, width, height);
        }
    }

    private record FakeHostSnapshot(DocumentId documentId, FakeDocument document, long version) implements HostSnapshot {
    }

    private static Map<String, FakeParameter> keyed(final List<FakeParameter> parameters) {
        final Map<String, FakeParameter> keyed = new LinkedHashMap<>();
        for (FakeParameter parameter : parameters) {
            keyed.put(parameter.id(), parameter.copy());
        }
        return keyed;
    }

    private static Map<String, FakeParameter> copyParameters(final Map<String, FakeParameter> parameters) {
        return keyed(new ArrayList<>(parameters.values()));
    }
}
