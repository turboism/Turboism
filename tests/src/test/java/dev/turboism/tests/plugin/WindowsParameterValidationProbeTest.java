package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.appearance.ControlAppearanceContribution;
import dev.turboism.sdk.ui.appearance.ControlAppearanceRegistry;
import dev.turboism.sdk.ui.appearance.ControlAppearanceSnapshot;
import dev.turboism.sdk.ui.appearance.ControlAppearanceStyle;
import dev.turboism.sdk.ui.appearance.ControlAppearanceTarget;
import dev.turboism.sdk.ui.appearance.NativeControlAppearance;
import dev.turboism.sdk.ui.appearance.NativeControlBackground;
import java.nio.file.Files;
import dev.turboism.sdk.ui.appearance.PresetColor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsParameterValidationProbeTest {

    @Test
    void queryCombinesSdkTextSearchAndMetadataFiltersInStableModelOrder() {
        final Parameters parameters = parameters(
            parameter("ParamEyeLOpen", "Eye Open", ParameterType.NORMAL, true, false, 0.8F),
            parameter("ParamEyeROpen", "Eye Open", ParameterType.BLEND_SHAPE, true, true, 0.7F),
            parameter("ParamMouthOpenY", "Mouth Open", ParameterType.BLEND_SHAPE, false, true, 0.2F),
            parameter("ParamUnknown", null, ParameterType.UNKNOWN, null, null, 0.0F)
        );

        final List<WindowsParameterValidationProbe.ParameterRow> rows =
            WindowsParameterValidationProbe.queryRows(
                parameters,
                new WindowsParameterValidationProbe.QuerySpec(
                    "eye",
                    WindowsParameterValidationProbe.SearchMode.CONTAINS,
                    WindowsParameterValidationProbe.TypeFilter.BLEND_SHAPE,
                    WindowsParameterValidationProbe.BooleanFilter.YES,
                    WindowsParameterValidationProbe.BooleanFilter.YES
                )
            );

        assertEquals(List.of("ParamEyeROpen"), ids(rows));
        assertEquals(Optional.of("Eye Open"), rows.get(0).name());
        assertEquals("Eye Open", rows.get(0).displayName());
        assertEquals(ParameterType.BLEND_SHAPE, rows.get(0).type());
        assertEquals(Optional.of(true), rows.get(0).repeat());
        assertEquals(Optional.of(true), rows.get(0).combined());
    }

    @Test
    void blankTextShowsAllParametersRegardlessOfSearchMode() {
        final Parameters parameters = parameters(
            parameter("ParamA", "A", ParameterType.NORMAL, false, false, 0.0F),
            parameter("ParamB", "B", ParameterType.BLEND_SHAPE, true, true, 1.0F)
        );

        for (final WindowsParameterValidationProbe.SearchMode mode
            : WindowsParameterValidationProbe.SearchMode.values()) {
            assertEquals(
                List.of("ParamA", "ParamB"),
                ids(WindowsParameterValidationProbe.queryRows(
                    parameters,
                    new WindowsParameterValidationProbe.QuerySpec(
                        "   ",
                        mode,
                        WindowsParameterValidationProbe.TypeFilter.ANY,
                        WindowsParameterValidationProbe.BooleanFilter.ANY,
                        WindowsParameterValidationProbe.BooleanFilter.ANY
                    )
                ))
            );
        }
    }

    @Test
    void selectionFollowsCubismWhenEnabledAndRetainsWindowSelectionWhenDisabled() {
        final List<WindowsParameterValidationProbe.ParameterRow> rows =
            WindowsParameterValidationProbe.queryRows(
                parameters(
                    parameter("ParamA", "A", ParameterType.NORMAL, false, false, 0.0F),
                    parameter("ParamB", "B", ParameterType.BLEND_SHAPE, true, true, 1.0F)
                ),
                new WindowsParameterValidationProbe.QuerySpec(
                    "",
                    WindowsParameterValidationProbe.SearchMode.CONTAINS,
                    WindowsParameterValidationProbe.TypeFilter.ANY,
                    WindowsParameterValidationProbe.BooleanFilter.ANY,
                    WindowsParameterValidationProbe.BooleanFilter.ANY
                )
            );

        assertEquals(
            Optional.of(new ParameterId("ParamB")),
            WindowsParameterValidationProbe.preferredSelection(
                rows,
                Optional.of(new ParameterId("ParamA")),
                Optional.of(new ParameterId("ParamB")),
                true
            )
        );
        assertEquals(
            Optional.of(new ParameterId("ParamA")),
            WindowsParameterValidationProbe.preferredSelection(
                rows,
                Optional.of(new ParameterId("ParamA")),
                Optional.of(new ParameterId("ParamB")),
                false
            )
        );
        assertEquals(
            Optional.of(new ParameterId("ParamA")),
            WindowsParameterValidationProbe.preferredSelection(
                rows,
                Optional.of(new ParameterId("ParamA")),
                Optional.of(new ParameterId("ParamFilteredOut")),
                true
            )
        );
    }

    @Test
    void setterReacquiresByIdAndReturnsTheAuthoritativePostWriteValue() {
        final AtomicInteger finds = new AtomicInteger();
        final float[] value = {0.0F};
        final Parameters parameters = new Parameters() {
            @Override public List<Parameter> all() { return List.of(); }
            @Override public Parameter find(final ParameterId id) {
                finds.incrementAndGet();
                return new Parameter() {
                    @Override public ParameterId id() { return id; }
                    @Override public float getValue() { return value[0]; }
                    @Override public float getMinimumValue() { return -1.0F; }
                    @Override public float getMaximumValue() { return 1.0F; }
                    @Override public float getDefaultValue() { return 0.0F; }
                    @Override public void setValue(final float requested) {
                        value[0] = Math.max(-1.0F, Math.min(1.0F, requested));
                    }
                };
            }
        };

        assertEquals(
            1.0F,
            WindowsParameterValidationProbe.setParameterValue(
                parameters,
                new ParameterId("ParamA"),
                5.0F
            )
        );
        assertEquals(
            -1.0F,
            WindowsParameterValidationProbe.setParameterValue(
                parameters,
                new ParameterId("ParamA"),
                -5.0F
            )
        );
        assertEquals(2, finds.get());
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsParameterValidationProbe.setParameterValue(
                parameters,
                new ParameterId("ParamA"),
                Float.NaN
            )
        );
        assertEquals(2, finds.get());
    }

    @Test
    void definitionEditorReacquiresByIdAndReturnsAuthoritativeMetadataAfterCommit() {
        final AtomicInteger finds = new AtomicInteger();
        final ParameterDefinition[] authoritative = {
            new ParameterDefinition(
                new ParameterId("ParamA"),
                "Angle A",
                -30.0F,
                0.0F,
                30.0F,
                ParameterType.NORMAL,
                false
            )
        };
        final Parameters parameters = new Parameters() {
            @Override public List<Parameter> all() { return List.of(); }
            @Override public Parameter find(final ParameterId id) {
                finds.incrementAndGet();
                final ParameterDefinition current = authoritative[0];
                if (!current.id().equals(id)) {
                    throw new NoSuchElementException(id.value());
                }
                return new Parameter() {
                    @Override public ParameterId id() { return authoritative[0].id(); }
                    @Override public Optional<String> name() {
                        return Optional.of(authoritative[0].name());
                    }
                    @Override public ParameterType type() { return authoritative[0].type(); }
                    @Override public Optional<Boolean> repeat() {
                        return Optional.of(authoritative[0].repeat());
                    }
                    @Override public float getValue() { return 12.0F; }
                    @Override public float getMinimumValue() {
                        return authoritative[0].minimumValue();
                    }
                    @Override public float getMaximumValue() {
                        return authoritative[0].maximumValue();
                    }
                    @Override public float getDefaultValue() {
                        return authoritative[0].defaultValue();
                    }
                    @Override public void setValue(final float ignored) { }
                    @Override public void updateDefinition(final ParameterDefinition requested) {
                        authoritative[0] = requested;
                    }
                };
            }
        };
        final ParameterDefinition requested = new ParameterDefinition(
            new ParameterId("ParamRenamed"),
            "Renamed",
            -45.0F,
            5.0F,
            45.0F,
            ParameterType.BLEND_SHAPE,
            true
        );

        final ParameterDefinition result = WindowsParameterValidationProbe.updateParameterDefinition(
            parameters,
            new ParameterId("ParamA"),
            requested
        );

        assertEquals(requested, result);
        assertEquals(2, finds.get());
    }

    @Test
    void combinedEditorReacquiresParametersAndReportsAuthoritativePairState() {
        final AtomicInteger finds = new AtomicInteger();
        final ParameterId firstId = new ParameterId("ParamA");
        final ParameterId secondId = new ParameterId("ParamB");
        final ParameterId[] partners = {null, null};
        final Parameters parameters = new Parameters() {
            @Override public List<Parameter> all() {
                return List.of(parameter(firstId, 0), parameter(secondId, 1));
            }
            @Override public Parameter find(final ParameterId id) {
                finds.incrementAndGet();
                if (firstId.equals(id)) return parameter(firstId, 0);
                if (secondId.equals(id)) return parameter(secondId, 1);
                throw new NoSuchElementException(id.value());
            }
            private Parameter parameter(final ParameterId id, final int index) {
                return new Parameter() {
                    @Override public ParameterId id() { return id; }
                    @Override public Optional<Boolean> combined() {
                        return Optional.of(partners[index] != null && index == 0);
                    }
                    @Override public Optional<ParameterId> combinedWith() {
                        return Optional.ofNullable(partners[index]);
                    }
                    @Override public float getValue() { return 0.0F; }
                    @Override public float getMinimumValue() { return -1.0F; }
                    @Override public float getMaximumValue() { return 1.0F; }
                    @Override public float getDefaultValue() { return 0.0F; }
                    @Override public void setValue(final float ignored) { }
                    @Override public void combineWith(final ParameterId partnerId) {
                        partners[0] = partnerId;
                        partners[1] = id;
                    }
                    @Override public void uncombine() {
                        partners[0] = null;
                        partners[1] = null;
                    }
                };
            }
        };

        assertEquals(
            Optional.of(secondId),
            WindowsParameterValidationProbe.combineParameters(parameters, firstId, secondId)
        );
        assertEquals(
            Optional.empty(),
            WindowsParameterValidationProbe.uncombineParameter(parameters, secondId)
        );
        assertEquals(4, finds.get());
    }

    @Test
    void combinedEditorOffersOrderedPartnerChoicesAndUsesTheOnlyChoiceForBlankInput() {
        final Parameters parameters = parameters(
            parameter("ParamA", "A", ParameterType.NORMAL, false, false, 0.0F),
            parameter("ParamB", "B", ParameterType.NORMAL, false, false, 0.0F),
            parameter("ParamC", "C", ParameterType.NORMAL, false, false, 0.0F)
        );

        assertEquals(
            List.of(new ParameterId("ParamB"), new ParameterId("ParamC")),
            WindowsParameterValidationProbe.partnerCandidates(
                parameters,
                new ParameterId("ParamA")
            )
        );
        assertEquals(
            new ParameterId("ParamB"),
            WindowsParameterValidationProbe.resolvePartnerId(
                "   ",
                List.of(new ParameterId("ParamB"))
            )
        );
        assertEquals(
            new ParameterId("ParamB"),
            WindowsParameterValidationProbe.resolvePartnerId(
                "   ",
                List.of(new ParameterId("ParamB"), new ParameterId("ParamC"))
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsParameterValidationProbe.combineParameters(
                parameters,
                new ParameterId("ParamA"),
                new ParameterId("ParamA")
            )
        );
    }

    @Test
    void folderBackgroundWriterReturnsAuthoritativeNativeAppearanceThroughRegistry() {
        final ParameterGroupId groupId = new ParameterGroupId("GroupFace");
        final Color[] color = {new Color(0.25F, 0.5F, 0.75F, 1.0F)};
        final List<ControlAppearanceTarget> writtenTargets = new java.util.ArrayList<>();
        final ControlAppearanceRegistry registry = new ControlAppearanceRegistry() {
            @Override public Registration register(final ControlAppearanceContribution contribution) {
                throw new UnsupportedOperationException();
            }

            @Override public ControlAppearanceSnapshot snapshot(final ControlAppearanceTarget target) {
                return new ControlAppearanceSnapshot(
                    Optional.of(new NativeControlAppearance(
                        new NativeControlBackground.Custom(color[0]), Optional.of(color[0])
                    )),
                    Optional.empty()
                );
            }

            @Override public void setNativeBackground(
                final ControlAppearanceTarget target,
                final NativeControlBackground background
            ) {
                writtenTargets.add(target);
                color[0] = ((NativeControlBackground.Custom) background).color();
            }
        };
        final Color requested = WindowsParameterValidationProbe.parseColor(
            "0.1", "0.2", "0.3", "0.4"
        );

        final NativeControlAppearance authoritative =
            WindowsParameterValidationProbe.setParameterFolderBackground(
                registry, groupId, requested
            );

        assertEquals(
            List.of(new ControlAppearanceTarget.ParameterFolder(groupId)),
            writtenTargets
        );
        assertEquals(requested, color[0]);
        assertEquals(new NativeControlBackground.Custom(requested), authoritative.background());
        assertEquals(Optional.of(requested), authoritative.effectiveBackground());
    }

    @Test
    void folderBackgroundWriterRejectsMissingRegistryTargetsAndValues() {
        final ParameterGroupId groupId = new ParameterGroupId("GroupFace");
        final ControlAppearanceRegistry registry = new ControlAppearanceRegistry() {
            @Override public Registration register(final ControlAppearanceContribution contribution) {
                throw new UnsupportedOperationException();
            }

            @Override public ControlAppearanceSnapshot snapshot(final ControlAppearanceTarget target) {
                return new ControlAppearanceSnapshot(Optional.empty(), Optional.empty());
            }

            @Override public void setNativeBackground(
                final ControlAppearanceTarget target,
                final NativeControlBackground background
            ) {
            }
        };
        assertThrows(NullPointerException.class, () ->
            WindowsParameterValidationProbe.setParameterFolderBackground(
                registry, null, new Color(1.0F, 0.0F, 0.0F, 1.0F)
            ));
        assertThrows(NullPointerException.class, () ->
            WindowsParameterValidationProbe.setParameterFolderBackground(
                null, groupId, new Color(1.0F, 0.0F, 0.0F, 1.0F)
            ));
        assertThrows(IllegalStateException.class, () ->
            WindowsParameterValidationProbe.setParameterFolderBackground(
                registry, groupId, new Color(1.0F, 0.0F, 0.0F, 1.0F)
            ));
    }

    @Test
    void customCandidateNeverEqualsTheSemanticBeforeState() {
        // The failing case: semantic is Custom(red) while the effective color differs.
        final NativeControlAppearance before = new NativeControlAppearance(
            new NativeControlBackground.Custom(new Color(1.0F, 0.0F, 0.0F, 1.0F)),
            Optional.of(new Color(0.9F, 0.1F, 0.1F, 1.0F))
        );

        final NativeControlBackground chosen =
            WindowsParameterValidationProbe.chooseCustomCandidate(before);

        assertFalse(
            chosen.equals(before.background()),
            "the chosen Custom request must differ from the semantic before-state"
        );
        assertEquals(
            new NativeControlBackground.Custom(new Color(0.0F, 1.0F, 0.0F, 1.0F)),
            chosen
        );
    }

    @Test
    void storedBackgroundPropertiesRoundTripEverySemanticKind() {
        final java.util.Properties properties = new java.util.Properties();
        final NativeControlBackground[] samples = {
            new NativeControlBackground.Default(),
            new NativeControlBackground.Preset(PresetColor.GRAY),
            new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F))
        };
        final String[] prefixes = {"folder.original", "part.requested", "deformer.original"};
        for (int index = 0; index < samples.length; index++) {
            WindowsParameterValidationProbe.storeStoredBackground(
                properties, prefixes[index], samples[index]
            );
        }

        for (int index = 0; index < samples.length; index++) {
            assertEquals(
                samples[index],
                WindowsParameterValidationProbe.parseStoredBackground(properties, prefixes[index])
            );
        }
    }

    @Test
    void primaryPeerResponseBudgetIsAtMostSixtySeconds() {
        assertEquals(600, WindowsParameterValidationProbe.PEER_RESPONSE_MAX_ATTEMPTS);
        assertEquals(100L, WindowsParameterValidationProbe.PEER_RESPONSE_POLL_MILLIS);
        assertTrue(
            WindowsParameterValidationProbe.PEER_RESPONSE_MAX_ATTEMPTS
                * WindowsParameterValidationProbe.PEER_RESPONSE_POLL_MILLIS <= 60_000L
        );
    }

    @Test
    void peerPluginStartupBudgetAllowsRealisticExactHostStartup() {
        assertEquals(2400, WindowsEditorObjectPeerValidationProbe.PEER_STARTUP_MAX_ATTEMPTS);
        assertEquals(100L, WindowsEditorObjectPeerValidationProbe.PEER_STARTUP_POLL_MILLIS);
        assertEquals(
            240_000L,
            WindowsEditorObjectPeerValidationProbe.PEER_STARTUP_MAX_ATTEMPTS
                * WindowsEditorObjectPeerValidationProbe.PEER_STARTUP_POLL_MILLIS
        );
        assertTrue(
            WindowsEditorObjectPeerValidationProbe.PEER_STARTUP_MAX_ATTEMPTS
                * WindowsEditorObjectPeerValidationProbe.PEER_STARTUP_POLL_MILLIS <= 240_000L
        );
    }

    @Test
    void scopeCloseRunningPhaseIsWrittenFromThePassedValues() throws Exception {
        final java.nio.file.Path artifact = saveTemp.resolve("native-control-background-validation.txt");
        WindowsParameterValidationProbe.writeRunningScopeClosePhase(
            artifact, "model-a", "AWT-EventQueue-0"
        );
        final String content = Files.readString(artifact);
        assertTrue(content.contains("status=RUNNING"), content);
        assertTrue(content.contains("phase=plugin-scope-close"), content);
        assertTrue(content.contains("modelId=model-a"), content);
        assertTrue(content.contains("hostThread=AWT-EventQueue-0"), content);
    }

    @Test
    void scopeCloseRunningPhaseReportsProgressContext() {
        final String phase = WindowsParameterValidationProbe.scopeCloseRunningPhase(
            "model-a", "AWT-EventQueue-0"
        );
        assertTrue(phase.contains("status=RUNNING"), phase);
        assertTrue(phase.contains("phase=plugin-scope-close"), phase);
        assertTrue(phase.contains("modelId=model-a"), phase);
        assertTrue(phase.contains("hostThread=AWT-EventQueue-0"), phase);
    }

    @Test
    void awaitPeerEvidenceReturnsTerminalContentAndTimesOutBounded() throws Exception {
        final java.nio.file.Path peerArtifact = saveTemp.resolve("peer-scope-close.txt");
        assertEquals(
            "",
            WindowsParameterValidationProbe.awaitPeerEvidence(peerArtifact, 5, 10L),
            "a missing peer artifact must time out within the bounded attempts"
        );
        final Thread writer = new Thread(() -> {
            try {
                Thread.sleep(150L);
                Files.writeString(peerArtifact, "status=PASS\nsecondPluginUsable=true\n");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        writer.start();
        final String evidence =
            WindowsParameterValidationProbe.awaitPeerEvidence(peerArtifact, 200, 10L);
        writer.join(5_000L);
        assertTrue(evidence.contains("status=PASS"), evidence);
        assertTrue(evidence.contains("secondPluginUsable=true"), evidence);
    }

    @Test
    void peerProbeMarkerWaitIsBoundedAndDetectsTheMarker() throws Exception {
        final java.nio.file.Path request = saveTemp.resolve("editor-object-peer-request.txt");
        assertFalse(WindowsEditorObjectPeerValidationProbe.awaitMarker(request, 5, 10L));
        final Thread writer = new Thread(() -> {
            try {
                Thread.sleep(150L);
                Files.writeString(request, "primaryScopeClosed=true\n");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        writer.start();
        assertTrue(WindowsEditorObjectPeerValidationProbe.awaitMarker(request, 200, 10L));
        writer.join(5_000L);
    }

    @Test
    void autoNativeControlBackgroundModesNeverShowTheValidationWindow() {
        assertFalse(WindowsParameterValidationProbe.showsValidationWindow("native-control-background"));
        assertFalse(WindowsParameterValidationProbe.showsValidationWindow("native-control-background-document-close"));
        assertFalse(WindowsParameterValidationProbe.showsValidationWindow("native-control-background-persist-write"));
        assertFalse(WindowsParameterValidationProbe.showsValidationWindow("native-control-background-persist-reopen"));
        assertFalse(WindowsParameterValidationProbe.showsValidationWindow("native-control-background-persist-final"));
        assertFalse(WindowsParameterValidationProbe.showsValidationWindow("fixed-api"));
        assertFalse(WindowsParameterValidationProbe.showsValidationWindow("fixed-api-document-close"));
        assertTrue(WindowsParameterValidationProbe.showsValidationWindow("matrix"));
        assertTrue(WindowsParameterValidationProbe.showsValidationWindow("binding-matrix"));
        assertTrue(WindowsParameterValidationProbe.showsValidationWindow("document-close"));
        assertTrue(WindowsParameterValidationProbe.showsValidationWindow("plugin-scope-close"));
        assertThrows(NullPointerException.class, () ->
            WindowsParameterValidationProbe.showsValidationWindow(null));
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path saveTemp;

    @org.junit.jupiter.api.AfterEach
    void clearFixtureProperty() {
        System.clearProperty("turboism.validation.fixture");
    }

    @Test
    void saveConfirmationDetectsACommittedFixtureWrite() throws Exception {
        final java.nio.file.Path fixture = saveTemp.resolve("fixture.cmo3");
        Files.writeString(fixture, "before");
        final java.nio.file.attribute.FileTime before =
            Files.getLastModifiedTime(fixture);
        final long beforeSize = Files.size(fixture);
        // Cross a coarse mtime boundary deterministically with a background one-time write.
        final Thread writer = new Thread(() -> {
            try {
                Thread.sleep(1_200L);
                Files.writeString(fixture, "after-save-content");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        writer.start();

        final WindowsParameterValidationProbe.SaveConfirmation confirmed =
            WindowsParameterValidationProbe.awaitSaveConfirmation(
                fixture, before, beforeSize, 10_000L, 50L
            );
        writer.join(15_000L);

        assertTrue(confirmed.confirmed(), "the committed write must be confirmed");
        assertEquals(beforeSize, confirmed.beforeSize());
        assertEquals(
            "after-save-content".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            confirmed.afterSize()
        );
        assertTrue(confirmed.afterMtimeMillis() >= confirmed.beforeMtimeMillis());
    }

    @Test
    void saveConfirmationFailsClosedWhenTheFixtureNeverChanges() throws Exception {
        final java.nio.file.Path fixture = saveTemp.resolve("static.cmo3");
        Files.writeString(fixture, "unchanged");
        final java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(fixture);

        final WindowsParameterValidationProbe.SaveConfirmation confirmation =
            WindowsParameterValidationProbe.awaitSaveConfirmation(
                fixture, before, Files.size(fixture), 400L, 40L
            );

        assertFalse(confirmation.confirmed(), "a never-changing fixture must fail closed");
    }

    @Test
    void saveConfirmationRejectsMissingFixturePaths() {
        assertThrows(IllegalArgumentException.class, () ->
            WindowsParameterValidationProbe.awaitSaveConfirmation(
                saveTemp.resolve("missing.cmo3"),
                java.nio.file.attribute.FileTime.fromMillis(0L),
                0L,
                100L,
                10L
            ));
        System.clearProperty("turboism.validation.fixture");
        assertThrows(IllegalArgumentException.class, () ->
            WindowsParameterValidationProbe.fixturePath());
        System.setProperty("turboism.validation.fixture", saveTemp.toString());
        assertEquals(saveTemp, WindowsParameterValidationProbe.fixturePath());
    }

    @Test
    void storedBackgroundParsingFailsClosedOnMalformedProperties() {
        final java.util.Properties properties = new java.util.Properties();
        assertThrows(IllegalArgumentException.class, () ->
            WindowsParameterValidationProbe.parseStoredBackground(properties, "missing"));
        properties.setProperty("bad.type", "rainbow");
        assertThrows(IllegalArgumentException.class, () ->
            WindowsParameterValidationProbe.parseStoredBackground(properties, "bad"));
        properties.setProperty("bad2.type", "custom");
        properties.setProperty("bad2.red", "not-a-number");
        properties.setProperty("bad2.green", "0");
        properties.setProperty("bad2.blue", "0");
        properties.setProperty("bad2.alpha", "1");
        assertThrows(IllegalArgumentException.class, () ->
            WindowsParameterValidationProbe.parseStoredBackground(properties, "bad2"));
        properties.setProperty("bad3.type", "preset");
        assertThrows(IllegalArgumentException.class, () ->
            WindowsParameterValidationProbe.parseStoredBackground(properties, "bad3"));
    }

    @Test
    void matrixRejectsSameSemanticRequestBeforeAnyWrite() {
        final NativeControlBackground request =
            new NativeControlBackground.Custom(new Color(0.1F, 0.2F, 0.3F, 0.4F));

        assertThrows(IllegalStateException.class, () ->
            WindowsParameterValidationProbe.requireDistinctBackgroundRequest(request, request));
        assertThrows(IllegalStateException.class, () ->
            WindowsParameterValidationProbe.requireDistinctBackgroundRequest(
                new NativeControlBackground.Default(),
                new NativeControlBackground.Default()
            ));
        WindowsParameterValidationProbe.requireDistinctBackgroundRequest(
            request,
            new NativeControlBackground.Default()
        );
        WindowsParameterValidationProbe.requireDistinctBackgroundRequest(
            new NativeControlBackground.Preset(PresetColor.BLUE),
            new NativeControlBackground.Preset(PresetColor.RED)
        );
    }

    @Test
    void defaultKeyformLockWriterReturnsAuthoritativeState() {
        final boolean[] locked = {false};
        final CubismModel model = new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-a"); }
            @Override public boolean defaultKeyformLocked() { return locked[0]; }
            @Override public void setDefaultKeyformLocked(final boolean value) { locked[0] = value; }
            @Override public Parameters parameters() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.ParameterGroups parameterGroups() {
                throw unsupported();
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
            @Override public void update() { throw unsupported(); }
        };

        assertEquals(true, WindowsParameterValidationProbe.setDefaultKeyformLock(model, true));
        assertEquals(false, WindowsParameterValidationProbe.setDefaultKeyformLock(model, false));
    }

    @Test
    void actionFailureDescriptionIncludesCauseChain() {
        final IllegalStateException failure = new IllegalStateException(
            "Combined update failed",
            new IllegalAccessException("fixture")
        );

        assertEquals(
            "IllegalStateException: Combined update failed"
                + " <- IllegalAccessException: fixture",
            WindowsParameterValidationProbe.failureDescription(failure)
        );
    }

    @Test
    void definitionInputRejectsMalformedNumbersAndUnknownTypeBeforeMutation() {
        assertEquals(
            new ParameterDefinition(
                new ParameterId("ParamA"),
                "Angle A",
                -30.0F,
                0.0F,
                30.0F,
                ParameterType.NORMAL,
                false
            ),
            WindowsParameterValidationProbe.parseDefinition(
                " ParamA ",
                " Angle A ",
                " -30 ",
                " 0 ",
                " 30 ",
                ParameterType.NORMAL,
                false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsParameterValidationProbe.parseDefinition(
                "ParamA", "Angle A", "bad", "0", "30", ParameterType.NORMAL, false
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsParameterValidationProbe.parseDefinition(
                "ParamA", "Angle A", "-30", "0", "30", ParameterType.UNKNOWN, false
            )
        );
    }

    @Test
    void setterInputAcceptsFiniteFloatsAndRejectsNonFiniteOrMalformedText() {
        assertEquals(12.5F, WindowsParameterValidationProbe.parseFiniteValue(" 12.5 "));
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsParameterValidationProbe.parseFiniteValue("NaN")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsParameterValidationProbe.parseFiniteValue("Infinity")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WindowsParameterValidationProbe.parseFiniteValue("not-a-number")
        );
    }

    @Test
    void exactNamePreservesDuplicatesAndUnknownBooleanFilterIsExplicit() {
        final Parameters parameters = parameters(
            parameter("ParamEyeLOpen", "Eye Open", ParameterType.NORMAL, true, false, 0.8F),
            parameter("ParamEyeROpen", "Eye Open", ParameterType.BLEND_SHAPE, true, true, 0.7F),
            parameter("ParamUnknown", null, ParameterType.UNKNOWN, null, null, 0.0F)
        );

        assertEquals(
            List.of("ParamEyeLOpen", "ParamEyeROpen"),
            ids(WindowsParameterValidationProbe.queryRows(
                parameters,
                new WindowsParameterValidationProbe.QuerySpec(
                    "Eye Open",
                    WindowsParameterValidationProbe.SearchMode.EXACT_NAME,
                    WindowsParameterValidationProbe.TypeFilter.ANY,
                    WindowsParameterValidationProbe.BooleanFilter.ANY,
                    WindowsParameterValidationProbe.BooleanFilter.ANY
                )
            ))
        );
        final List<WindowsParameterValidationProbe.ParameterRow> unknownRows =
            WindowsParameterValidationProbe.queryRows(
                parameters,
                new WindowsParameterValidationProbe.QuerySpec(
                    "",
                    WindowsParameterValidationProbe.SearchMode.CONTAINS,
                    WindowsParameterValidationProbe.TypeFilter.ANY,
                    WindowsParameterValidationProbe.BooleanFilter.UNKNOWN,
                    WindowsParameterValidationProbe.BooleanFilter.UNKNOWN
                )
            );
        assertEquals(List.of("ParamUnknown"), ids(unknownRows));
        assertEquals(Optional.empty(), unknownRows.get(0).name());
        assertEquals("ParamUnknown", unknownRows.get(0).displayName());
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException();
    }

    private static List<String> ids(
        final List<WindowsParameterValidationProbe.ParameterRow> rows
    ) {
        return rows.stream().map(row -> row.id().value()).toList();
    }

    private static Parameters parameters(final Parameter... parameters) {
        final List<Parameter> values = List.of(parameters);
        return new Parameters() {
            @Override
            public List<Parameter> all() {
                return values;
            }

            @Override
            public Parameter find(final ParameterId id) {
                return values.stream()
                    .filter(parameter -> parameter.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException(id.value()));
            }
        };
    }

    private static Parameter parameter(
        final String id,
        final String name,
        final ParameterType type,
        final Boolean repeat,
        final Boolean combined,
        final float value
    ) {
        return new Parameter() {
            @Override public ParameterId id() { return new ParameterId(id); }
            @Override public Optional<String> name() { return Optional.ofNullable(name); }
            @Override public ParameterType type() { return type; }
            @Override public Optional<Boolean> repeat() { return Optional.ofNullable(repeat); }
            @Override public Optional<Boolean> combined() { return Optional.ofNullable(combined); }
            @Override public float getValue() { return value; }
            @Override public float getMinimumValue() { return -30.0F; }
            @Override public float getMaximumValue() { return 30.0F; }
            @Override public float getDefaultValue() { return 0.0F; }
            @Override public void setValue(final float ignored) { }
        };
    }
}
