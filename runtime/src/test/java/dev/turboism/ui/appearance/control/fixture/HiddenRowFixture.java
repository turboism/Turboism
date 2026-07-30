package dev.turboism.ui.appearance.control.fixture;

public final class HiddenRowFixture {
    private HiddenRowFixture() {
    }

    public static Object row(final String id) {
        return new HiddenRow(new PublicSource(id));
    }

    private record HiddenRow(PublicSource value) {
        public PublicSource source() {
            return value;
        }
    }

    public static final class PublicSource {
        private final PublicId id;

        private PublicSource(final String id) {
            this.id = new PublicId(id);
        }

        public PublicId getId() {
            return id;
        }
    }

    public record PublicId(String value) {
        public String getIdString() {
            return value;
        }
    }
}
