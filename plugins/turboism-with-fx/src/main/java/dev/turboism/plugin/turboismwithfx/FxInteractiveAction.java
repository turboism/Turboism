package dev.turboism.plugin.turboismwithfx;

import java.util.List;

/** Exact fx v0.0.5 interactive commands that keep credentials inside fx. */
enum FxInteractiveAction {
    SHELL("fx-shell.action.shell", List.of()),
    LOGIN_VERCEL("fx-shell.action.login-vercel", List.of("login", "vercel")),
    LOGIN_CODEX("fx-shell.action.login-codex", List.of("login", "codex")),
    LOGIN_GROK("fx-shell.action.login-grok", List.of("login", "grok")),
    SETUP_GATEWAY_KEY("fx-shell.action.setup-gateway-key", List.of("setup")),
    LOGOUT_VERCEL("fx-shell.action.logout-vercel", List.of("logout", "vercel")),
    LOGOUT_CODEX("fx-shell.action.logout-codex", List.of("logout", "codex")),
    LOGOUT_GROK("fx-shell.action.logout-grok", List.of("logout", "grok"));

    private final String localizationKey;
    private final List<String> arguments;

    FxInteractiveAction(final String localizationKey, final List<String> arguments) {
        this.localizationKey = localizationKey;
        this.arguments = List.copyOf(arguments);
    }

    String localizationKey() {
        return localizationKey;
    }

    List<String> arguments() {
        return arguments;
    }
}
