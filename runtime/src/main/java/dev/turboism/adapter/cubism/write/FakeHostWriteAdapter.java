package dev.turboism.adapter.cubism.write;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.TransactionException;
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

    private final Map<String, FakeDocument> documents = new LinkedHashMap<>();
    private String activeDocumentId;
    private boolean hostPresent = true;
    private long version;

    public synchronized void addDocument(final String documentId, final String documentName, final FakeModel model) {
        final FakeDocument document = new FakeDocument(documentId, documentName, model.copy());
        documents.put(document.id(), document);
        activeDocumentId = document.id();
        version++;
    }

    public synchronized double parameterValue(final String modelId, final String parameterId) {
        final FakeDocument document = documents.get(activeDocumentId);
        if (document == null || !document.model().id().equals(modelId)) {
            throw new IllegalArgumentException("Unknown model " + modelId);
        }
        final FakeParameter parameter = document.model().parameters().get(parameterId);
        if (parameter == null) {
            throw new IllegalArgumentException("Unknown parameter " + parameterId);
        }
        return parameter.value();
    }

    @Override
    public synchronized HostSnapshot capture(final DocumentId documentId) throws TransactionException {
        final FakeDocument document = document(documentId);
        return new FakeHostSnapshot(documentId, document.copy(), version);
    }

    @Override
    public synchronized void apply(final DocumentId documentId, final List<WriteParameterCommand> commands)
        throws TransactionException {
        final FakeDocument document = document(documentId);
        for (WriteParameterCommand command : commands) {
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

    private void applyCommand(final FakeDocument document, final WriteParameterCommand command) throws TransactionException {
        final FakeModel model = document.model();
        if (!model.id().equals(command.modelId().value())) {
            throw error(command.commandId(), UNKNOWN_MODEL, "Unknown model " + command.modelId().value());
        }
        final FakeParameter parameter = model.parameters().get(command.parameterId().value());
        if (parameter == null) {
            throw error(command.commandId(), UNKNOWN_PARAMETER, "Unknown parameter " + command.parameterId().value());
        }
        parameter.setValue(command.value());
    }

    private FakeDocument document(final DocumentId documentId) throws TransactionException {
        final FakeDocument document = documents.get(documentId.id());
        if (document == null) {
            throw error(documentId.id(), UNKNOWN_DOCUMENT, "Unknown document " + documentId.id());
        }
        return document;
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

    private record FakeDocument(String id, String name, FakeModel model) {
        private FakeDocument copy() {
            return new FakeDocument(id, name, model.copy());
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
