package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorPartBasicSettingsSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartTreeSelectorContract;
import org.junit.jupiter.api.Test;

class EditorPartNameSelectorContractTest {

    @Test
    void exact5302RecordVerifiesTheCompletePartNameContract() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.3.02");

        assertTrue(resolver.authorizesFeature(
                EditorPartNameSelectorContract.ADAPTER_SLICE_ID,
                EditorPartNameSelectorContract.CAPABILITY_ID,
                EditorPartNameSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartNameSelectorContract.ADAPTER_SLICE_ID,
                EditorPartNameSelectorContract.WRITE_CAPABILITY_ID,
                EditorPartNameSelectorContract.WRITE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartBasicSettingsSelectorContract.ADAPTER_SLICE_ID,
                EditorPartBasicSettingsSelectorContract.READ_CAPABILITY_ID,
                EditorPartBasicSettingsSelectorContract.READ_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartBasicSettingsSelectorContract.ADAPTER_SLICE_ID,
                EditorPartBasicSettingsSelectorContract.WRITE_CAPABILITY_ID,
                EditorPartBasicSettingsSelectorContract.WRITE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartTreeSelectorContract.ADAPTER_SLICE_ID,
                EditorPartTreeSelectorContract.CAPABILITY_ID,
                EditorPartTreeSelectorContract.REQUIRED_ALIASES));
    }

    @Test
    void exact5203RecordAdvertisesPartNameReadAndWrite() throws Exception {
        final var resolver = EditorSelectorContractTestPaths.editorModelResolver("5.2.03");

        assertTrue(resolver.authorizesFeature(
                EditorPartNameSelectorContract.ADAPTER_SLICE_ID,
                EditorPartNameSelectorContract.CAPABILITY_ID,
                EditorPartNameSelectorContract.REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartNameSelectorContract.ADAPTER_SLICE_ID,
                EditorPartNameSelectorContract.WRITE_CAPABILITY_ID,
                EditorPartNameSelectorContract.WRITE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartBasicSettingsSelectorContract.ADAPTER_SLICE_ID,
                EditorPartBasicSettingsSelectorContract.READ_CAPABILITY_ID,
                EditorPartBasicSettingsSelectorContract.READ_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartBasicSettingsSelectorContract.ADAPTER_SLICE_ID,
                EditorPartBasicSettingsSelectorContract.WRITE_CAPABILITY_ID,
                EditorPartBasicSettingsSelectorContract.WRITE_REQUIRED_ALIASES));
        assertTrue(resolver.authorizesFeature(
                EditorPartTreeSelectorContract.ADAPTER_SLICE_ID,
                EditorPartTreeSelectorContract.CAPABILITY_ID,
                EditorPartTreeSelectorContract.REQUIRED_ALIASES));
    }
}
