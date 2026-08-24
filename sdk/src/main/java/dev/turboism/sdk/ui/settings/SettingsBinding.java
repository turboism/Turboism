package dev.turboism.sdk.ui.settings;


import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Plugin-owned read/write binding used by one declarative settings control. */
public interface SettingsBinding<T> {

    T read();

    void write(T value);

    static <T> SettingsBinding<T> of(
        final Supplier<? extends T> reader,
        final Consumer<? super T> writer
    ) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(writer, "writer");
        return new SettingsBinding<>() {
            @Override public T read() { return reader.get(); }
            @Override public void write(final T value) { writer.accept(value); }
        };
    }
}
