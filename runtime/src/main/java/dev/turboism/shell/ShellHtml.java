package dev.turboism.shell;

/** Escaping shared by the core shell's HTML-rendered labels and guide pages. */
final class ShellHtml {

    private ShellHtml() {}

    /** Escapes the five HTML metacharacters so localized text renders literally, never as markup. */
    static String escape(final String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
