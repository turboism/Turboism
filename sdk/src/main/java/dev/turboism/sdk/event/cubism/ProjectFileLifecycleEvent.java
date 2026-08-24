package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed lifecycle states shared by model and animation project-file operations. */
public sealed interface ProjectFileLifecycleEvent extends TurboismEvent
    permits ProjectFileLifecycleEvent.Before,
            ProjectFileLifecycleEvent.On,
            ProjectFileLifecycleEvent.After {

    ProjectFileOperation operation();

    record Before(ProjectFileOperation operation) implements ProjectFileLifecycleEvent {
        public Before { operation = Objects.requireNonNull(operation, "operation"); }
    }

    record On(ProjectFileOperation operation, ProjectContentSnapshot content)
        implements ProjectFileLifecycleEvent {
        public On {
            operation = Objects.requireNonNull(operation, "operation");
            content = Objects.requireNonNull(content, "content");
        }
    }

    record After(ProjectFileOperationResult result) implements ProjectFileLifecycleEvent {
        public After { result = Objects.requireNonNull(result, "result"); }
        @Override public ProjectFileOperation operation() { return result.request(); }
    }
}
