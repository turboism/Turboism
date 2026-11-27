package dev.turboism.internal;

/** Canonical first-party origins, repository paths, and documentation URLs. */
public final class TurboismEndpoints {

    public static final String SITE_ORIGIN = "https://turboism.dev";
    public static final String WWW_ORIGIN = "https://www.turboism.dev";
    public static final String API_HOST = "api.turboism.dev";
    public static final String API_ORIGIN = "https://" + API_HOST;
    public static final String DOWNLOAD_ORIGIN = "https://dl.turboism.dev";
    public static final String PLUGIN_ORIGIN = "https://plugin.turboism.dev";
    public static final String DOCS_ORIGIN = "https://docs.turboism.dev";
    public static final String THANKS_ORIGIN = "https://thanks.turboism.dev";

    public static final String GITHUB_REPOSITORY = "turboism/Turboism";
    public static final String GITHUB_REPO_URL = "https://github.com/" + GITHUB_REPOSITORY;
    public static final String GITHUB_RELEASES_URL = GITHUB_REPO_URL + "/releases";
    public static final String GITHUB_BLOB_MAIN_PREFIX = GITHUB_REPO_URL + "/blob/main/";
    public static final String GITHUB_ISSUES_URL = GITHUB_REPO_URL + "/issues";
    public static final String GITHUB_LICENSE_URL = GITHUB_BLOB_MAIN_PREFIX + "LICENSE";
    public static final String GITHUB_EULA_URL = GITHUB_BLOB_MAIN_PREFIX + "EULA.md";
    public static final String GITHUB_RAW_CONTENT_PREFIX =
            "https://raw.githubusercontent.com/" + GITHUB_REPOSITORY + "/";
    public static final String GITHUB_API_CONTENTS_PREFIX =
            "https://api.github.com/repos/" + GITHUB_REPOSITORY + "/contents/";

    public static final String RELEASES_PREFIX = API_ORIGIN + "/v1/releases/";
    public static final String PLUGIN_CATALOG_URL = PLUGIN_ORIGIN + "/api/v2/catalog.json";
    public static final String PLUGIN_CATALOG_SIGNATURE_URL = PLUGIN_ORIGIN + "/api/v2/catalog.json.sig";
    public static final String PLUGIN_ARTIFACTS_API_PREFIX = API_ORIGIN + "/v1/plugins/artifacts/";
    public static final String PLUGIN_ARTIFACTS_CDN_PREFIX = DOWNLOAD_ORIGIN + "/plugins/";

    public static final String DOWNLOAD_PAGE_URL = SITE_ORIGIN + "/download";
    public static final String SPONSOR_PAGE_URL = SITE_ORIGIN + "/sponsor";
    public static final String PLUGIN_MARKET_URL = SITE_ORIGIN + "/plugins";
    public static final String PLUGIN_MARKET_ITEM_PREFIX = PLUGIN_MARKET_URL + "/";

    private TurboismEndpoints() {}
}
