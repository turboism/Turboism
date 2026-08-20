package dev.turboism.plugin.uitheme.b1.domain;

/**
 * The descriptive header of a theme package.
 *
 * <p>The compact constructor normalizes rather than rejects: absent text becomes the empty
 * string and absent enums take a default, so a decoded package always has usable metadata.
 * The one exception is {@code parentId}, where empty is normalized to null because an empty
 * parent id means "no parent", not "a parent named nothing". Nothing here is validated for
 * meaning - an id may still be unusable, and the parent may not exist.
 *
 * @param id the theme's identifier; not defaulted or validated here
 * @param name the display name; not defaulted or validated here
 * @param description free text about the theme, empty when absent
 * @param author the package author, empty when absent
 * @param url the package's home or source URL, empty when absent
 * @param version the author-declared version string, empty when absent
 * @param parentId the id of the theme this one derives from, or null when it stands alone
 * @param base the appearance the palette targets, {@link ThemeBase#ANY} when absent
 * @param icons the icon variant to pair with, {@link ThemeIcons#LIGHT} when absent
 * @param builtIn whether the package claims to be shipped with the plugin, false when absent;
 *                a claim only - {@link BuiltinThemeCatalog} decides what is actually reviewed
 */
public record ThemePackageMetadata(
    String id,
    String name,
    String description,
    String author,
    String url,
    String version,
    String parentId,
    ThemeBase base,
    ThemeIcons icons,
    Boolean builtIn
) {
    public ThemePackageMetadata {
        description = description == null ? "" : description;
        author = author == null ? "" : author;
        url = url == null ? "" : url;
        version = version == null ? "" : version;
        parentId = parentId == null || parentId.isEmpty() ? null : parentId;
        base = base == null ? ThemeBase.ANY : base;
        icons = icons == null ? ThemeIcons.LIGHT : icons;
        builtIn = builtIn == null ? Boolean.FALSE : builtIn;
    }
}
