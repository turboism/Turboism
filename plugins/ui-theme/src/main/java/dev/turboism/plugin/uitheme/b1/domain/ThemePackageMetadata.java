package dev.turboism.plugin.uitheme.b1.domain;

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
