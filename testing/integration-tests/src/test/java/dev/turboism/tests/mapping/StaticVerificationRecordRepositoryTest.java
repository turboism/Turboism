package dev.turboism.tests.mapping;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.cubism.VerifiedClipMaskHostOperations;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.exportsettings.VerifiedProtectedExportHostOperations;
import dev.turboism.mapping.schema.MappingPackValidator;
import dev.turboism.mapping.verification.AutoBackupVerificationManifest;
import dev.turboism.mapping.verification.BoundingBoxOverlayButtonVerificationManifest;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.ControlAppearanceVerificationManifest;
import dev.turboism.mapping.verification.CorePublicApiTrustRoots;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.EmbeddedPanelVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.MainToolbarVerificationManifest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.ProtectedExportVerificationManifest;
import dev.turboism.mapping.verification.RecentPreviewVerificationManifest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticVerificationRecordValidator;
import dev.turboism.mapping.verification.StatusBarVerificationManifest;
import dev.turboism.mapping.verification.TopMenuVerificationManifest;
import dev.turboism.mapping.verification.WorkspaceControlVerificationManifest;
import dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class StaticVerificationRecordRepositoryTest {

    private static final Path PROJECT_ROOT =
            Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")));
    private static final Path RECORDS = PROJECT_ROOT.resolve("compatibility/cubism/verification");

    /**
     * SHA-256 pin of every emitted exact-version record registered by {@link #withVersionedRecords}.
     * Records pinned by the hand-maintained tables above keep their literals there.
     */
    private static final Map<String, String> EXPECTED_VERSIONED_RECORD_SHA256 = Map.ofEntries(
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-clipmask.json",
                    "db94c1cc5e004da1638664b234916c1c54f6ec9b320198d20c35a0f23bb46d6b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-core-model-read.json",
                    "809a31bc6b49f7fe222e31a59eb806bb07a6a85f6b889b1cf1f498a53175af34"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-editor-model.json",
                    "f9e989aeb877341a301478ee52bc12b62de18bc9f607a246ba16ec84445cd363"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-performance-render-scene.json",
                    "0c0e801a3cb8d30c98013c999bc35f5d9760bd93617b4ef00332ba5444556ec8"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-project-workspace.json",
                    "b40e7a8a4ea7d275016a4d5c2a36011cd639a0ed1d2a87e53df8b56dd9cbdef6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-startup-suppression.json",
                    "3632dd8cb9263c42b9475a384c36c3687a3c6ccf9edd82e4e09591c8366ed1bc"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-ui-bounding-box-overlay.json",
                    "69e5769dae413aa4adc2e2c871deb2136d7f9d2783be88bc03214ccf8a2e7104"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-ui-control-appearance.json",
                    "0b78c304595a2b4ca2396687c78748ca755a0a68c0300583a5beb8550c3a2cca"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-ui-embedded-panel.json",
                    "3023481a22cd62fc047c342cb1a94c9592f706a43c943160d2ae0d774edebcfd"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-ui-main-toolbar.json",
                    "66d881758f0367ce90e9f8f6f846fc40cce0fb19f4afc12970d07480835f8a4d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-ui-status-bar.json",
                    "d2e0093605237e3923a29f9c9bce64279a8a6dcb7eb039a6eff79e7169cc04e6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.00-workspace-control.json",
                    "906f0a4a4a86ea2d832194922cbfb75ff973e51fa8fc0d888e443fc4e951ac40"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-clipmask.json",
                    "5a2dfdd5556bfd9c0091eb6e33b6d747fa9b17d933388df0b44be5d181858439"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-core-model-read.json",
                    "923183070586c691dec9dbe4e0d4598c54b03823b53c474067b4eb8ad212999a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-editor-model.json",
                    "89dcf2c4a91ca3129f355d5b74565847e008c5b58df050fd8e63452f2983bd14"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-performance-render-scene.json",
                    "ee055893f0408eeee0922f5ab293b19fb4385c28bdb67ce9d1b4814bd3996133"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-project-workspace.json",
                    "ceacdfef2fd70a86891565aeb38f5a9c0c39ab3a6a2349e278e39209bebb7498"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-startup-suppression.json",
                    "ec629a0b8860d3a7411d5ad10279d1cf36ad4c80f0669e2b2f58cdcf6d1be99f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-ui-bounding-box-overlay.json",
                    "80337dcd7e12083e63983096344c8a0bc12153a39d4b24b4855e1cb8e6903f97"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-ui-control-appearance.json",
                    "2d0e8f87d4ca82a30a5ed783cf3fbebc63449d3a029f85e92bf84cc64b85802f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-ui-embedded-panel.json",
                    "a9f8cccfda1d2859148d4d277e6f55d69cfc6d6d02bf1a963f22be33ebc23abc"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-ui-main-toolbar.json",
                    "ba4f5025e43283817dd02b9c87c7bffc3a1b0fea74248cef4fc7202cabe126a6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-ui-status-bar.json",
                    "62c4e3373d4854dfea83293df19d017fc479f81f4116f6e2ea5be9081114c83e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.01-workspace-control.json",
                    "8661758ce38edb5b6ab2e823bbb1c864f8983721c2e8c931333953bf39b26a25"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-clipmask.json",
                    "1ecfda0a85e9da753d7505fe4e2824b9319c5acd4b2f5a872af7f7a5b9632f51"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-core-model-read.json",
                    "94ce065f4c53d126a55f991c34c2457aa1c8dc4c9ade706cb3da8f5c3353cbf8"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-editor-model.json",
                    "fa885ad3cc586fe01e9abf1616782c6bbd9eb48998d669f18cffcfdeb26645b2"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-performance-render-scene.json",
                    "86a0bcaa95ecdce7e61a6f85c26e7e5caf3e3b89e887b154e7f117777fdbab00"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-project-workspace.json",
                    "93cf43ac6dd6bb1d6f3dfdd87571f0a87da7b3b9ceb5068333fef091f6b16a5d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-startup-suppression.json",
                    "3c729fc2d35980b17b764b5f282a16f73b57557213d71170d984fb6526d8e14e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-ui-bounding-box-overlay.json",
                    "b15a16738e57cf632ba8dbaf7e77b01bdb1e075a18fb7b31610d74867046eb08"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-ui-control-appearance.json",
                    "963a279d1f1922ebcc5841efd5e1e579356564d06b7369c8f91852d56d02508b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-ui-embedded-panel.json",
                    "430815ff5efbf92a098f0c64098e67f0a9596f5dc866e7b9f3ef6a9038a16b5a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-ui-main-toolbar.json",
                    "bf693c2572b16c0cd45241f6f6ee2d270ce49ffc3c8612e6eab7d51c3efdedf8"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-ui-status-bar.json",
                    "57e81251ca2ab6add4c00180d6aff0abd1bcc2b8ca3e9960f6d3651130b563b3"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.02-workspace-control.json",
                    "cb217386abcf1f1350fe3642e2dea56780b7969f922c5c823435c0a0fd28e57b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-clipmask.json",
                    "27e152c2cf5c9773e590713f4b7c89ed4f636ff9f30401c50ca3f381338f60ab"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-core-model-read.json",
                    "37440cfb8245a18618314dbe5c573ca5a18734e5fe489a657ee0cbc01d60e592"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-editor-model.json",
                    "4d4cae7da09ff2de6236667b4520ca39e6153ccd668a4a789e0024bf2842efa4"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-performance-render-scene.json",
                    "48334a520d8cfd93e87dd12e0490a847b30bab6a6717d30cdb20e19cbc10a281"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-project-workspace.json",
                    "807cc9a7a44481550d03b5841c588b7d85f28092f6cbe8b3e32dc00d428921b3"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-startup-suppression.json",
                    "26ca077a610d7bc95342e2bc1f7b160aa70e18451debe3edc63de23d3c74be45"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-ui-bounding-box-overlay.json",
                    "1e45248c61ce8656b6c70b1fe0a643efa0a11b357bec3cbea58f4e4288a305b6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-ui-control-appearance.json",
                    "e1f8b13a2ffdeccca7eac1d8435ee7220b8a8affdecc76a046692147d03840a4"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-ui-embedded-panel.json",
                    "62ef05c31f6ea7d6ad813dfa9e493109d330885aaf3eb94054e23aa5c5b42545"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-ui-main-toolbar.json",
                    "995e4abe99fcb351b42398aa6f49731fd524a3d3e06955bf5b0df12fc51b5364"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-ui-status-bar.json",
                    "b67509d95a765e2ac2c6859ae0b1a5d273ac67bdc8d161e2f56d071681992cee"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.03-workspace-control.json",
                    "feb8c5f4e3a1157b543fba2497e48e76beb47ce41d5024e899841434eb523fb1"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-clipmask.json",
                    "24fff5f0b50d213c0bc24e174cd93c09567855be94984defdcb0da9f551d566e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-core-model-read.json",
                    "9a2d2293747f013c4dc3266b80a8f85c92d71cbefd39063c389f053b538fd634"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-editor-model.json",
                    "cedd94977274949ce8bba2497462cf93db1e2bf07b47bd4bb99c45cc3c064f31"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-performance-render-scene.json",
                    "056f94c4a0f9fac09395c39a26e8d299329679e4c5d3e073bb6dffac1380f7b9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-project-workspace.json",
                    "90e129de7abc95a2d3ec2cc425e9cde081a1815c513d4b670785c67544c7b551"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-startup-suppression.json",
                    "1648bcb9393fe400151d5ede9a88fb403212006e0610b4e9ff57ff129e50f34d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-ui-bounding-box-overlay.json",
                    "1d155cf163506eebe4f187c1782f1f9318a5a432bb97b3a5d67586ee24181894"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-ui-control-appearance.json",
                    "6369a0015202b6e861a724ea40c72fb1ae97efdc78beaacf4ea06d7596782356"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-ui-embedded-panel.json",
                    "fdf43e48c12fe092c8d08f32bf75d4624c062bbc81881cf635d5ead67a05c06c"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-ui-main-toolbar.json",
                    "ceb4585c33fb72ce4a80be0095d8ec778dbdef9742d2c111ebb35b1fb00ae5fd"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-ui-status-bar.json",
                    "65ef87a6e84af39b113d00015a30bb6bbab21d3887e3ca2d32b3e61cb6210de4"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.04-workspace-control.json",
                    "eff30ea9b4ca56f3f997c212aae6d1b4a1cbfabd3aa7e7eaf2079c23e6b936c3"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-clipmask.json",
                    "4a007c8187e27be949a411b3808269031d9f5a980ee379e16186f5d836e3adb6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-core-model-read.json",
                    "9614bad35ffda237cca80c9e2f86cf2d0a953fe68d3b93a558f2022e403eab2b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-editor-model.json",
                    "a4bbba26fab8dac31a3520d155dfa1a4f8f3da431a7168e74e66f13fe42e6f85"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-performance-render-scene.json",
                    "0f4f9edc73f0fd058e147856c1807feb44adc5c87d57b1b8189b63447391497e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-project-workspace.json",
                    "be8f0854a536c2d794af431e1978e4c1fd25b219567245f48afe56b6b7f2011f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-startup-suppression.json",
                    "da6664b31aa48c59639a0f38f094da3a69f0ea4076ca806736037fa0211098e1"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-ui-bounding-box-overlay.json",
                    "8dedf8b3d5cc4e62aa0987fc1c0b9b77cd0fa4f18014f77627be58a915edc14f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-ui-control-appearance.json",
                    "72672d2b57a7ded0c3696bdd8b3bcde3e7b55c16d54fa88d80f36ce1da599a2e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-ui-embedded-panel.json",
                    "f48c0dc72fcb60d948973d712fdf85507c4c2539611580934ea0dff4e36b7d3e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-ui-main-toolbar.json",
                    "75a9d555eceb8ca10bbc27e5230f05a4df1431418bf4090854954139460ff4d5"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-ui-status-bar.json",
                    "18bdb508c01da893cbdb72ce5ae5973dad1b7aecdadc559a4d7aa17add07d811"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.05-workspace-control.json",
                    "5dc9cc184a0640a94211e33a494dbbccb42f53d9044a694a02d0768c92f73fad"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-clipmask.json",
                    "1a56bb42773340d9980dea77c33ec799999b0741fe74bb8c30f49e4895f1f46b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-core-model-read.json",
                    "121bac9058589f7ee898866854efcc16a1d1a2bcf3e808b0dbe7fb86ffcdf7ea"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-editor-model.json",
                    "0d89651b6f2cec7370b7b8334a99d1f0a8b6826a1a07c1d054942fc48db5076b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-performance-render-scene.json",
                    "d5630d741bf550822dd6fec27424097fb34be214e216ba35d68a7733bc1488d5"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-project-workspace.json",
                    "15e606772a1f4bb887c54f123d540cccdfac3bc98f0bd83a4f37eca8f100bf70"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-startup-suppression.json",
                    "fbbdc7be033e3b077a37f1d899c6e96b83a9237ccf0cd4d42a6e0e3fd589b480"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-ui-bounding-box-overlay.json",
                    "4972dbe73990faa570480ed7eb111527333137c9f957a2c345fae77c5faf3c2d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-ui-control-appearance.json",
                    "bd214bd402771aeeadacf7ea2304a5dbaf31148b28fe927c45126e8c835945a4"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-ui-embedded-panel.json",
                    "b159e5a49aa31115d7e08967ab2d6615a860f1f78ceab44ac5a305e3535aaf83"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-ui-main-toolbar.json",
                    "308c7579f554c02518af6fc214020e728254474509c101d75cf96be36a745d11"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-ui-status-bar.json",
                    "76732db14d07cbad99ba5db891fc286e6d8c624d599d2cde110be02c7ce87249"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.0.06-workspace-control.json",
                    "4ec277e301556ab34b2cf688277b6c0a4b3ad5c2b54b64bace84e302e6c820c6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-autobackup.json",
                    "7924a2fbf2e0168515e86662dbfcdda1b2001ff58a3a2827bb042ad6c42a7f70"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-clipmask.json",
                    "9533b5ed917c11c1c48f015fd082031f9d0485d0442634fcb7afe0c414e6c2fd"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-core-model-read.json",
                    "ff1c84d87c6542fb77d16d5d5cfe4f250f694ff993b9ad975568f8838ef92607"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-editor-model.json",
                    "19dcc61ad8327a148e508048637bfdd12da305f70806d7068720382f5b521254"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-performance-render-scene.json",
                    "ef5f8be9ab1475584171b406bab7cc6841b63f7863b7fd4d2b9f7d621eeb8459"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-project-workspace.json",
                    "ff166689f97a554d7ad96bb28e0f913b8c9b5cbeac5a033d5b2689f9e8ffa362"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-protected-export.json",
                    "815316a522f1c4b9028e87ff36f575841f18026fd8eb3ce510e60f330a606277"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-startup-suppression.json",
                    "f50253702272c89f06a427bfb4bb32012009dc553a3a9f8930674bca908c0d4d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-ui-bounding-box-overlay.json",
                    "3d0f5951611cf209ee6881ff639f02721a4be9b314357a993d3af796bfd58672"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-ui-control-appearance.json",
                    "aadb8a5d478ab348a95d9671d71225699bb9048bc2bd60baa2c065ae3285a474"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-ui-embedded-panel.json",
                    "dacaeb39d39d95426b9930f05aa91a893c2b9c1a64449c70036c17ff3d8e30fc"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-ui-main-toolbar.json",
                    "38afde0da6a0a720590662c9d07d8185bdc0bf84902ee33656051cc534a415ae"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-ui-status-bar.json",
                    "1ee00d047745563aff9d6c370118c79859f00ddaaf1b2bea645f1ea628cd750a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-ui-top-menu.json",
                    "b2fc927adc5774bf3dd44ce208d407ee9bddbb4e2bda551b46167abfcf6a1067"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.00-workspace-control.json",
                    "7efd9c00b185fd06d548d720a5ada7030dc862e63fc830001f1fc4c929122542"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-autobackup.json",
                    "c49580fecfaa8da987c5bc6b5788666f5307874985494d835aebe518b07c9b21"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-clipmask.json",
                    "e38e59076a664e918b512eebb29986130426a988e9e694dc9234e1d5004f1d0f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-core-model-read.json",
                    "f34105cd17537a556d40afc68e83998bfec16c4c178d7cb56edbd157fbd90b79"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-editor-model.json",
                    "7db1746a3771a87dc2f2c203c16b80cdf705c167eedef09516be3b605309fce7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-performance-render-scene.json",
                    "f041410bc9ad1e73e59f02a0e80a999bead1bfea710864dbd5fa7b68cf851d37"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-project-workspace.json",
                    "90b76b9bea5899746a268cb50022b532234dfd6affd372e84fed0f6f52b0067c"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-protected-export.json",
                    "451a6e0a8ac2e774d1022cd1373574f1038eb2ca6ba414674229b2abea146c26"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-startup-suppression.json",
                    "edca63423279b9258beafbf26a810ac4f7c8e7432e04fa68a4924eeaca037a2e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-ui-bounding-box-overlay.json",
                    "711473f1a1d0b199a625ff05f5586e644297a11737d9c138216e46aaad5ed510"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-ui-control-appearance.json",
                    "8ebdcf3116459d952d7fe18220d31730deea924dbf9d4dfb3d6d11f7c1409fdd"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-ui-embedded-panel.json",
                    "444c5cc36e24db1326c32dfaadbc9098a14090d533a50e7c0d03b80dd54f2f38"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-ui-main-toolbar.json",
                    "63b537d348736aed32385d9b974c45e961e1dc38e6be246e5cd3c649605b42b9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-ui-status-bar.json",
                    "5214e8302ff56668acad84b5e9e7f7f75ef4c6d7fc3f56246255a9d0f1618aba"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-ui-top-menu.json",
                    "accf66e095273a8c89c0214a40c26658b3709a5a5977982742bddee5d5e4bdee"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.01-workspace-control.json",
                    "384109a81204c9de4d337cafd393addf84e63c40b3db131a49981c80570174d3"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-autobackup.json",
                    "dffcb31e09b0d2ae885b2bb24864de73a4b6bfdbbdd7fdc4e4f8c47a4c15e26b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-clipmask.json",
                    "2eeefa12bd70c4c722aec61ebd79de959b43022d45e368a8cbf46084612523a3"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-core-model-read.json",
                    "97261ec0ce9168dba63b6fff520349677b9447e79aaceaf7042549d20ee2d7fe"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-editor-model.json",
                    "199a177995c80eeccca5798d1ba0b855a9d477b1f4b807b1eecf5c99ebc59d4d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-performance-render-scene.json",
                    "dbaa0b377eae6253cef15e55396e59b74e2ec6b24a7c583ddbb99506060f6893"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-project-workspace.json",
                    "9641eff9b85b1d4b7d4324eab06d0f1bd6f9ebc47d4cce0586db1b973da25974"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-protected-export.json",
                    "ef7cccc0f9242d8c8d26f1a653a3055dbb32aab7ddc07b0dc754501f9f7069b7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-startup-suppression.json",
                    "9931684491d30efced3b97aa9731d72e23d498cb3a0bee94513a37e486c0e4ab"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-ui-bounding-box-overlay.json",
                    "667ca9bd40c7de5e948392833adb1aa6c58ab2e4f445da31e7bd4ba0053d7261"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-ui-control-appearance.json",
                    "11d8a648d493989d5bff6a0f40bf718ee2653ee2ac03df3e840632426326f20d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-ui-embedded-panel.json",
                    "eee00bc4bd7c9a6c858eb93f1a6b9fbb9815c03dfd2980598ec17e6d24b7b277"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-ui-main-toolbar.json",
                    "02145c5cb5d611da2b00a1077660abf2255455e1dc8e1c7bea62d0091b9edea7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-ui-status-bar.json",
                    "95dff6e1a975c247730ebf1a94963a552e2a4322dff79bbde2f62024c76ae248"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-ui-top-menu.json",
                    "8c40c5aebaed793f5e4a656b630df688e1a7563226a927b227ed0f0e34fcf86d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.02-workspace-control.json",
                    "21cc73b6588bbbc6aada146b8e610c6489fc9b31d0d24856c86474f5de66b744"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-autobackup.json",
                    "cef00026efb8a3209e5511e0f92f3326b02f2c0ab06d446cdf19b3826826c0a7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-clipmask.json",
                    "7fe95e7872f95adb7f264a80f036cef2510fddbd614f1fa85763364b9ccadc68"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-core-model-read.json",
                    "6bb75a353edc6318515f49cc3f2385c5f020972fad46312f3896b504f71cf3c0"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-editor-model.json",
                    "bb71d88d7590eeec452e677d0a065e986e7321d719d791d334808f40223ecbee"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-performance-render-scene.json",
                    "0d1936d04c64b15b276ea22a7bc94ba57fa842d62eb20e933484c3e2d2059195"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-project-workspace.json",
                    "5939eec8760694ffc3ac8037ac603fe9a3cdb742c48301fd5b7f0f31a8a72ad5"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-protected-export.json",
                    "53d56ed216a490c7a3212574e94db8122be984e0535340fc8fb3f19de1840a0a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-startup-suppression.json",
                    "1866851ccf99763de57caa75c0240fbf60058fd61f992fd5a002ca78505039ec"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-ui-bounding-box-overlay.json",
                    "0a526541f812236b4fa5f65a56c7f93f35fae33f204bfdbcae2a51d4881a69ae"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-ui-control-appearance.json",
                    "9afbcffe5fc14f54bcc27c60819a5b2bde3fe3c5a48ca50dcff02860d2e07609"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-ui-embedded-panel.json",
                    "9a08822f42aade6e776089075f35e3286c3d3245573f5003ec0cf47f94b19ea6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-ui-main-toolbar.json",
                    "8b308e58ff356e0d921eb93f06d9848741f5590961eeb6c3a7a432781de01666"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-ui-status-bar.json",
                    "2b971bb18a18e8fb731182403ac082bba1d67eee97d625eb0214a45f3de2271a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-ui-top-menu.json",
                    "217d9735312043a7c6a539c75d3c869a37cb850cfda80102f899bf9f23af9d9a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.1.03-workspace-control.json",
                    "c4357ae20f9a7746d232dcf1ba6fd942c94abea0488bb6ce1fe4cc20b609385f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-autobackup.json",
                    "79c166c56405fade9da4f1e11a738a0c4dc25b046c53e1586db3d46cec4e3108"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-clipmask.json",
                    "ea059c3e8c43ad9003652760ccf09f3873fdf777c70128c6b375958daa68b1a9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-core-model-read.json",
                    "65ea4588d38888d1259f32334626aa6833e509e9276439081eb5e2ed03b725b9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-editor-model.json",
                    "95bc9546793f49fb94b1ac72c09135f5cf43136ec148f522416a730296c5f400"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-performance-render-scene.json",
                    "62cd1206d4b5f4a564613c946f7ff614075f358b6bb23734f4b8632fda50226d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-project-workspace.json",
                    "d46a7b2e910744a4914454bb1ed0a033ad8511e7aa1d114e2f2cd301afa5bbc6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-protected-export.json",
                    "140c5e5fc5414d3833e20eaa8790cd13fc0dd6402a9caf3b02028341a082f760"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-startup-suppression.json",
                    "b2f4e8e9928b4cef5a70a870276db56a204fa202c49a7dc54cc2aa2747d7bee7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-ui-bounding-box-overlay.json",
                    "040ef9546b4254fd3b6f424d88d1dcfb8395920400d385f498a434d07616cb5f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-ui-control-appearance.json",
                    "279970b2be435a914c45f140883e14fe2cc1ad34a2a6acd1e84954648a2ed49f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-ui-embedded-panel.json",
                    "1b8a9821c542c5a96195f617420ad78f07e78223490c3bcf792b789425233671"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-ui-main-toolbar.json",
                    "2ddf60f03f78c26729b620713dcb782d730584c408a380e7263cffff4a0d6ec1"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-ui-status-bar.json",
                    "99f7b75d85641baef301c530232bf7e247b4c5e2f4c0732880c2586bb1fd1338"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-ui-top-menu.json",
                    "52dda952935fb19991458d7c6fc858f145f61129fad7b5ad5f3fca46ca7ea4be"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.00-workspace-control.json",
                    "95a8a8e83ac69863ce0846356f96a9a0d25a56181856350ba5fd6ec133ad3e85"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-autobackup.json",
                    "174c58d18ba9e25db40c16154f6056afc786615f1c3185c0579541d611c78a11"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-clipmask.json",
                    "6ec7f47455df3a63ad155509f5d1a0fe8c18c649f74b1946f5d056076464ec09"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-core-model-read.json",
                    "6d809e62ff7d4a4f5232d508d01b4c6d699b2c476fdd20f0d86fe9e10d93bfd9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-editor-model.json",
                    "76cca5a389f801fd9da8abf9ef869095ba73cefccf608ea31d60aa7b3c1ab372"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-performance-render-scene.json",
                    "e77c845e9590a21e405f57aa8fa305740d3e5a6d094426896068a82224763166"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-project-workspace.json",
                    "8fbc907afd7756b3cb1ce5d0b8ed40690a097372f35bc0d77d70b5e3eba55bdd"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-protected-export.json",
                    "7d89497423b43af7c2ca19b6ed83ff9c871335ae78013a637718a353d02625ec"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-startup-suppression.json",
                    "d6b077f8ac036457c0855626a85ebd49d29b2428bb5f8510ff7ee4a2eeb9eaeb"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-ui-bounding-box-overlay.json",
                    "5fb9c825ee6b63e41c2ac2d54742fd38bac9e75d6f1de376be5fdfd047ec240f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-ui-control-appearance.json",
                    "1b1a0215e00895c1a578abe474c7b860bb7c3a605cf0e9279be84bbcad154dc4"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-ui-embedded-panel.json",
                    "1372a9b148a8a5397976d2738a7ad820d8515993af843f8b5b0ddb81a67cc963"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-ui-main-toolbar.json",
                    "3bb289f919496e5cadabf15e7e00a1106045ba7efbcd5fdd0fd675fbafd3042e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-ui-status-bar.json",
                    "d01cce46452cc37fc7834ebf0e5d8562e059699c23c165d9d7bbd43d2e3d20fd"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-ui-top-menu.json",
                    "bd1088d88723c6e814d31a8c6ca323a678a165477a5a08e6b2f9630638d403b0"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.01-workspace-control.json",
                    "07a2bfe4ad24da515776bd37d7523675e16aced55fe9a5f6f452f9ffb120dda0"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-autobackup.json",
                    "640d43d479838e505df7fec7403bb7e9de1d8147a9f4373e04db41c2bdba2c69"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-clipmask.json",
                    "49ad9cd2b7e30470889112b563c62e3a0220935e18ad44eb341cbd0a00a82e7b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-core-model-read.json",
                    "b4eb38839a49bda9d9f881be905342ccbb8ac3d2fc826114b1224d7d1b9b2187"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-editor-model.json",
                    "c5b8ae8d2018fee62eb4341b6b0fe1ba7aab4c0a564b5120106a65b1ab730bb9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-performance-render-scene.json",
                    "7fd3fbded80cb2b1d2d7fccd0e625b2b2c6dc8b3850bac0d14a56c30986eb27b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-project-workspace.json",
                    "4d7542de5a035effc2e9203613ec8a5909e48b7a93b192aea75fa7fa765a7dc7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-protected-export.json",
                    "d23c1097e2e681f8e10d0002fc623341ba95b7cef53fa23d26f16fdd2ee4c283"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-startup-suppression.json",
                    "0a135375ae7f474d17f08686ea599d80ee430502a2518fd4239b8c651c30199a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-ui-bounding-box-overlay.json",
                    "152a90ad5fd4550578a3e41c2331de74a5b70372482c92f1ce52ff69da658b12"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-ui-control-appearance.json",
                    "80bda40853381da6f9cc060a198987f054f873a969ec443b15d6e10c7a8a9a0a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-ui-embedded-panel.json",
                    "5e507a6751135f9420f72483d228a3e8c4d6b37b5cbf82d32021a9b1fef504bf"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-ui-main-toolbar.json",
                    "4cda4157eb190f56542c6d9a97ea46ad50d6947e7ce227457cbdaebfee8e92c7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-ui-status-bar.json",
                    "808272e9be814361d695da532b60aad97308fab64192dafde864c127e27f5aa0"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-ui-top-menu.json",
                    "25e5f4f9d6aaee4e9d5a798c79fdfdaf7e5965869b3a5ecf04442ccefe6635f7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.02-workspace-control.json",
                    "693860d996de9aa2965228a0508db3cda1a3123ed83b8a42b2ff010aeb7e9f5f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-autobackup.json",
                    "88f03d25d83e21104bd3703874447c58b56104ce8a250bc7fcdcace14e1831c0"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-clipmask.json",
                    "80e63e64c9a69e943d4c231818667f94bcbd56f32784c3d003b46a42e7dd08a3"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-core-model-read.json",
                    "26c4204656d9fc396044d8412de62a987922d2f49dd7481685ffb518755b7037"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-editor-model.json",
                    "6ca758d3ac0b3712feb57ed9a6501967e40c99b0e70a2111606e4be8ebb84154"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-performance-render-scene.json",
                    "399c09052ddb715f1d3ca257d84d41d851b74da3aba18a84f0fefc5956fd4cab"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-project-workspace.json",
                    "beb9b253b8f3a0c8f0b7171ea0776ac4d24ab5ae0ffd002d86295577ce8eb248"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-protected-export.json",
                    "628c2e99aa09c4dc5f1850cde4e2dfe8eaadd2628e6f6e1e61b2d12fe53190f7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-startup-suppression.json",
                    "c94ee1e5a6768683da50fe0f0e767c5adcedb95391e49db15c19fe770cae6bc5"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-ui-bounding-box-overlay.json",
                    "d11b021baf4eb38179924540880179ee8d0569da8d674643072cfbd53cc0611c"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-ui-control-appearance.json",
                    "91796c8e80faf12f61043409b25d3403a9263b9391ed7a50275f9c8a38183215"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-ui-embedded-panel.json",
                    "d4aa6e2e20feac7c0f9af1aea365c44ac00d1a913e68085c9f80a89d9cfb70ef"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-ui-main-toolbar.json",
                    "c2c72349a5131ed832077f7880b3062d08cf98aef488704a0ea755d3a4e65a5d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-ui-status-bar.json",
                    "875c8b8adcb75f8f70caf1b458c382ed65daf81a1b8a2a316c975bb2f33997cf"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-ui-top-menu.json",
                    "365bcaa24c0df8aad0e9ae28d4ef7cb72d05780f87d720c8181145784e6e042b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.2.03-workspace-control.json",
                    "1acf44eab99b480b1981c3c14f0229d2f0c5cfefdcc739fa831100a42496be94"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-autobackup.json",
                    "e6cc97172283ef02416d0cb7e3a928e61bbe6b95a503f5c87e071375091861f9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-clipmask.json",
                    "25a1be2b2f80889f3771d2cc2a18c5d180d3817584d8fd6fac72de6ea5aa9f7d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-core-model-read.json",
                    "b8ec9a7e42f339a603150b530c6afabb083ec44545b0645cf35a9010eb95d10b"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-editor-model.json",
                    "5763382640c0ae0df9418b00393e185b4a44a6b57acf4f849c4a7d5dd8c38c26"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-performance-render-scene.json",
                    "018283db4056e2d32ab8aadcef6924e21534c31ec4d14d054c5ffcf9bc95a6a1"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-project-workspace.json",
                    "7094047cb892a889042dcc5f1fc0dd070ecc225c58b573b08f1323b9c812eb6c"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-protected-export.json",
                    "c8b9723f7dcdbaee5276aa7d919eefa21a9854339cd944ac7251b22a67034bff"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-startup-suppression.json",
                    "6ff82f5b2dc6e83441d3846b2341a42e800e3b81065bb86f1130abc8c0a60335"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-ui-bounding-box-overlay.json",
                    "36232d3b71aa933c1e528db20d5029b63e4cdd7e7f2dda7b063962c19b716e73"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-ui-control-appearance.json",
                    "0a3816f3d159f5d5a8259116a982293b701c9722cc6717120f9850c1b943e13e"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-ui-embedded-panel.json",
                    "aa0b2de375bc9f3dcd7c146703783a7b12a2287530c95c7b31c4a8d3136aacca"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-ui-main-toolbar.json",
                    "bc9e1ad843884a25a5539968f250336cdcd032c2aa03ef69b66418b6b2ae3016"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-ui-status-bar.json",
                    "d1c7507af73650f705626574ac107518667fd3c5743794a320cad62bf500d550"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-ui-top-menu.json",
                    "055730481e6206b9d0c037420b6269dc3a985128a80db414aa0b6e780feab5d2"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.00-workspace-control.json",
                    "cebf52d1fa3c16dde96da7cbbbdcd4991e9d83eaa4d13ee71ce9bc592f3c29d1"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-autobackup.json",
                    "cc59841bb9b95c20aab72647a30603e30030d9fa8eadd529417c06d06416d7f3"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-clipmask.json",
                    "de81ab6d48e34bbdac1e4e70dfc40a245e0fd96babf7bd8b076fa6f325e5d6df"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-core-model-read.json",
                    "dd5f3c76ff6348337de62cbb0bf76c67bcb273729b1714636d0c1494c9134212"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-editor-model.json",
                    "9c0647ea9aa730339e63a6993fe9c4c0fdadb3f306649f84d9c14533c8bb6d16"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-performance-render-scene.json",
                    "a8c0701e86cc783f9c380ab1b50895d2293b3d11e06a04c96ddde6583b544678"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-project-workspace.json",
                    "a571fceea7551e2785f1162f366cd25f671fe51f702d398aa9a9143c12dd32a1"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-protected-export.json",
                    "1e259fd7d6ffd8d5158e359d75a2b74188f15b44121c4824611d90ae57151772"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-startup-suppression.json",
                    "6090f777b5905888721e82364d0edcadc43c2d69e42cdc6797a261d3e64cfa1c"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-ui-bounding-box-overlay.json",
                    "e93b791d5fa1ee92e334641b97b441cb49a9bb76f7049890def1235752893b67"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-ui-control-appearance.json",
                    "c094bdfd7ceaf7077f582d615cf900d13ece99c8721a876eaffe2ba447e25d29"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-ui-embedded-panel.json",
                    "eb9cb6cc7113c9b8003b14fc9001ab4abb597828c8e3c62ac300f40b8a177f5d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-ui-main-toolbar.json",
                    "24f16147be56f5e0fc5cee7ca855a26d696d83ecb16947a776722ae26a0be30f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-ui-status-bar.json",
                    "5df0fd3512e9eadb986f2afb936424cf453a9da9a0ace8a65e427ca52d1e406c"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-ui-top-menu.json",
                    "59e995acf03d5dd6eebc65fe1e14495e590bc3ca1ddc14172255e8c1250766ff"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.01-workspace-control.json",
                    "b10b00734c74bcaff94f71a0995d1aa27252fda01de4adf4d793a98b465a821d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-autobackup.json",
                    "8b1249d7af4221bc55bb30946b6062e2abf080e8809aebe2901e11fcc56e2f68"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-clipmask.json",
                    "be2426a11a4f771eb1f61c5287e8e332d3a4515c53feffe6de86c962b2746be9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-core-model-read.json",
                    "6650108a950731fcc8d98210b40398a9d3c6fece8fda19b220bcdf58a09bd8da"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-editor-model.json",
                    "49cb92c45301882bfb043dc49df954b56b545a94fb89762dddcfbc3f63e15e41"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-performance-render-scene.json",
                    "cf401975fa0e033d7e093a54117a4f78ff5ff218bb9b7c4f7eae5bb7e8f57a92"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-project-workspace.json",
                    "a691669d9205a62e0461b85f2397df1f7d7c05389277356fd8e192225e8a26d7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-protected-export.json",
                    "d844de2f9de9992da4a1657977ff62ef62d9c195dd166a97ae14fa20e1c3409a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-startup-suppression.json",
                    "9a3ee4f684edb37c4c44e3ef549b806bc0caec4a0ebf94144f4524edcb15059c"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-ui-bounding-box-overlay.json",
                    "743851942ba762ae69c6e1da01274f6605be2153c0081249ed81095ef84321a6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-ui-control-appearance.json",
                    "70f4979093b0d93cbaa9abb24f769b667c62956ee44a67226fa65db185976059"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-ui-embedded-panel.json",
                    "53cdafe0a97e0723eb3ad2870622d18705a7f2df49b7d7cb81b70c14f3b1b554"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-ui-main-toolbar.json",
                    "6e0bcf516cebda2df38730e8ecf0a5cb3549fc040a9395413a2e67bac8e26786"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-ui-status-bar.json",
                    "251dc10959a9a90f8532f013501442dbf08e9508d0ebf6655aafd3dcd3aa9839"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-ui-top-menu.json",
                    "c2c439cfae920168f401955b23df3869944673c1a2c1d533116ba6e143b88872"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.02-workspace-control.json",
                    "ac52277ae32f617d7aeced4845f15adf91c414b9e206d935a7a904f62e5ecf47"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-autobackup.json",
                    "639036d9d0bc35a480ef18521efc43eec29f26a95c4eea7b136f52e40cf0d74d"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-clipmask.json",
                    "f4702a5914c8fa8f6e8b266c675bc0c9e21b2667364ddca05b313f10b1351459"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-core-model-read.json",
                    "a0a47ca2b03c2267147019460caf17ca2241f1389bdc9b1f307aac57132deb67"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-editor-model.json",
                    "f38de80eea22fc5fc64e82caffe7e50edbc18249978874f160470df000a15849"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-performance-render-scene.json",
                    "1f46c24551b7b5ccc63bb379a4498d5b3166fa20916499ca9b5c8c52da307ba5"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-project-workspace.json",
                    "401e121ca6d06634b04f59737660c2eec95fee37f8603b5eacd86fd74e7c7fa9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-protected-export.json",
                    "de6066d86686cd3445b921086e7747167cf30b2fa89eb8efe49da064242d99b7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-startup-suppression.json",
                    "cce0b42e2d5af0ea4044c1538957b0046deb6c7e391b104fbe765e915b8c13ff"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-ui-bounding-box-overlay.json",
                    "fd67451595cbf68ca3084504c730daee3b110577e67852680db64b3a6f81e000"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-ui-control-appearance.json",
                    "6c771b8564af0569ed1c47064f13db20a0da5b8ef3dd1d90d06905311c58f710"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-ui-embedded-panel.json",
                    "efd21e78301e09cb5cdf4d4d80fdbaa78c6f676f8bb813ef28af41a2656dca55"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-ui-main-toolbar.json",
                    "cee7eca72cd4abf180d0e01905381907c8ae35056d68dc4b2f7911d2e2d4fee4"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-ui-status-bar.json",
                    "f74152ef76ac4f88daf22ae3670aa8296757f18818f98d8763deea913cd3a1f2"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-ui-top-menu.json",
                    "8468ba23f43f3176cd20b92851348c719b1b8811b90c5af055fd26bf10f0bfea"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.03-workspace-control.json",
                    "1a1be264fe64c6dc3e2f1ec10cb854a475540ebf1270b93e6d50e01ae6b801fc"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-autobackup.json",
                    "c7288fde9a84cf2aa16afdd2524127bad215ad49e7e00d3beb60aafcceb7f1d9"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-clipmask.json",
                    "f86602256cc5660232ccb1ca1528ff8f1f4d71eec05f1f10756828a919e7e552"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-core-model-read.json",
                    "b03a59d4d03bb9e148732880670aa5510d7d11396fe5cfe6bb1e1b84e84725d4"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-editor-model.json",
                    "04344716b6358aaa284042e90954ea29938aa4e43c977f7c51548e7cfcd28d82"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-performance-render-scene.json",
                    "2e0ff4eace38df67a740e0aec94b1de01a6c379a53d97265018b9ad79d88c234"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-project-workspace.json",
                    "e104f4f4a6ff6d6b78784b060d9f484777454297630cfad22bce12c97f93bef4"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-protected-export.json",
                    "9b3428c6d3b535706c71a735308372d57bec3d966586ed1dc1740f7dae83b0a7"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-startup-suppression.json",
                    "755822d66d9020d2b8b2bdb8f88066b435be03322f49c10b44e658d07ace8867"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-ui-bounding-box-overlay.json",
                    "e1fb165727797556cbfd0bb26c96e6e65690a69ef27f9e3c62b222177e9c6bd2"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-ui-control-appearance.json",
                    "25fcc92f0b9221e0c254e9e2d3d25eba9aa73e79efc54911c19a9a59cc882a8a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-ui-embedded-panel.json",
                    "a5566144fff833b27634ea4b5c7f81e7552b01eced588c2ccfe5f11a819bc3d6"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-ui-main-toolbar.json",
                    "d0e97586ec578cfadf9ae34440c4f7d5df149e2a1199949e5775348513572f4a"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-ui-status-bar.json",
                    "3b4bc997856e1bca40ab59e61a92f5ce0e892fd279dec522515ed0019bede43f"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-ui-top-menu.json",
                    "e88a381e0b6ebb671a08fdcf8f8818c2b3a546924e8143eab61cf2a6c27f5dd8"),
            Map.entry(
                    "compatibility/cubism/verification/cubism-5.3.04-workspace-control.json",
                    "1f3ff698e6c33c2b45c9ee9ac279a19ef2450a8e96da1084712f38b54f2d112d"));

    private static final Map<String, SliceExpectation> EXPECTATIONS = withVersionedRecords(withProtectedExports(
            with5303(withPerformance(withClipMask52(withAutoBackup(withStatusBar(withWorkspaceControl(
                    withControlAppearance53(withBoundingBoxOverlays(withTopMenus(withEmbeddedPanels(Map.of(
                            "compatibility/cubism/verification/cubism-5.2.03-project-workspace.json",
                            new SliceExpectation(
                                    "m15.cubism-5.2.03.project-workspace.static",
                                    ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                                    "5.2.03",
                                    "cubism-5.2.03",
                                    ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
                                    "Live2D_Cubism.jar",
                                    ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                                    ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                                    "beb9b253b8f3a0c8f0b7171ea0776ac4d24ab5ae0ffd002d86295577ce8eb248",
                                    26,
                                    ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES,
                                    projectWorkspaceImplementationAliases(),
                                    projectWorkspaceMemberAliases(),
                                    projectWorkspaceAttestationClassAliases(),
                                    "cubism-5.2.03-project-workspace",
                                    Path.of(
                                            "compatibility/cubism/mapping-packs/draft/cubism-5.2.03-project-workspace.json"),
                                    Path.of("compatibility/cubism/profiles/draft/cubism-5.2.03.json"),
                                    "5.2.03",
                                    SliceKind.PROJECT_WORKSPACE),
                            "compatibility/cubism/verification/cubism-5.3.02-project-workspace.json",
                            new SliceExpectation(
                                    ProjectWorkspaceVerificationManifest.RECORD_5_3_02.verificationId(),
                                    ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
                                    ProjectWorkspaceVerificationManifest.RECORD_5_3_02.cubismVersion(),
                                    ProjectWorkspaceVerificationManifest.RECORD_5_3_02.profileId(),
                                    ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
                                    "Live2D_Cubism.jar",
                                    ProjectWorkspaceVerificationManifest.RECORD_5_3_02
                                            .artifact()
                                            .size(),
                                    ProjectWorkspaceVerificationManifest.RECORD_5_3_02
                                            .artifact()
                                            .sha256(),
                                    ProjectWorkspaceVerificationManifest.RECORD_5_3_02.recordSha256(),
                                    26,
                                    ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES,
                                    projectWorkspaceImplementationAliases(),
                                    projectWorkspaceMemberAliases(),
                                    projectWorkspaceAttestationClassAliases(),
                                    "cubism-5.3.02-m14-project-workspace",
                                    Path.of(
                                            "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-m14-project-workspace.json"),
                                    Path.of("compatibility/cubism/profiles/draft/cubism-5.3.02.json"),
                                    "5.3.02",
                                    SliceKind.PROJECT_WORKSPACE),
                            "compatibility/cubism/verification/cubism-5.2.03-core-model-read.json",
                            coreExpectation(
                                    "5.2.03",
                                    "5.2.03",
                                    36_237L,
                                    "85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e",
                                    "26c4204656d9fc396044d8412de62a987922d2f49dd7481685ffb518755b7037",
                                    80,
                                    "5.2.03"),
                            "compatibility/cubism/verification/cubism-5.3.02-clipmask.json",
                            new SliceExpectation(
                                    ClipMaskVerificationManifest.RECORD_5_3_02.verificationId(),
                                    ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
                                    ClipMaskVerificationManifest.RECORD_5_3_02.cubismVersion(),
                                    ClipMaskVerificationManifest.RECORD_5_3_02.profileId(),
                                    ClipMaskVerificationManifest.CAPABILITY_IDS,
                                    "Live2D_Cubism.jar",
                                    ClipMaskVerificationManifest.RECORD_5_3_02
                                            .artifact()
                                            .size(),
                                    ClipMaskVerificationManifest.RECORD_5_3_02
                                            .artifact()
                                            .sha256(),
                                    ClipMaskVerificationManifest.RECORD_5_3_02.recordSha256(),
                                    16,
                                    ClipMaskVerificationManifest.REQUIRED_ALIASES,
                                    VerifiedClipMaskHostOperations.REQUIRED_ALIASES,
                                    VerifiedClipMaskHostOperations.methodAliasesUsed(),
                                    VerifiedClipMaskHostOperations.classAliasesUsed(),
                                    "cubism-5.3.02-m15-clipmask",
                                    Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.3.02-m15-clipmask.json"),
                                    Path.of("compatibility/cubism/profiles/draft/cubism-5.3.02.json"),
                                    "5.3.02",
                                    SliceKind.CLIP_MASK),
                            "compatibility/cubism/verification/cubism-5.2.03-ui-main-toolbar.json",
                            new SliceExpectation(
                                    MainToolbarVerificationManifest.RECORD_5_2_03.verificationId(),
                                    MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
                                    MainToolbarVerificationManifest.RECORD_5_2_03.cubismVersion(),
                                    MainToolbarVerificationManifest.RECORD_5_2_03.profileId(),
                                    MainToolbarVerificationManifest.CAPABILITY_IDS,
                                    "Live2D_Cubism.jar",
                                    ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                                    ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                                    MainToolbarVerificationManifest.RECORD_5_2_03.recordSha256(),
                                    49,
                                    MainToolbarVerificationManifest.REQUIRED_ALIASES,
                                    MainToolbarVerificationManifest.REQUIRED_ALIASES,
                                    mainToolbarMethodAliases(),
                                    difference(
                                            MainToolbarVerificationManifest.REQUIRED_ALIASES,
                                            mainToolbarMethodAliases()),
                                    "cubism-5.2.03-ui-main-toolbar",
                                    Path.of(
                                            "compatibility/cubism/mapping-packs/draft/cubism-5.2.03-ui-main-toolbar.json"),
                                    Path.of("compatibility/cubism/profiles/draft/cubism-5.2.03.json"),
                                    "5.2.03",
                                    SliceKind.EDITOR_UI),
                            "compatibility/cubism/verification/cubism-5.3.02-ui-main-toolbar.json",
                            new SliceExpectation(
                                    MainToolbarVerificationManifest.RECORD_5_3_02.verificationId(),
                                    MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
                                    MainToolbarVerificationManifest.RECORD_5_3_02.cubismVersion(),
                                    MainToolbarVerificationManifest.RECORD_5_3_02.profileId(),
                                    MainToolbarVerificationManifest.CAPABILITY_IDS,
                                    "Live2D_Cubism.jar",
                                    MainToolbarVerificationManifest.RECORD_5_3_02
                                            .artifact()
                                            .size(),
                                    MainToolbarVerificationManifest.RECORD_5_3_02
                                            .artifact()
                                            .sha256(),
                                    MainToolbarVerificationManifest.RECORD_5_3_02.recordSha256(),
                                    49,
                                    MainToolbarVerificationManifest.REQUIRED_ALIASES,
                                    MainToolbarVerificationManifest.REQUIRED_ALIASES,
                                    mainToolbarMethodAliases(),
                                    difference(
                                            MainToolbarVerificationManifest.REQUIRED_ALIASES,
                                            mainToolbarMethodAliases()),
                                    "cubism-5.3.02-ui-main-toolbar",
                                    Path.of(
                                            "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-ui-main-toolbar.json"),
                                    Path.of("compatibility/cubism/profiles/draft/cubism-5.3.02.json"),
                                    "5.3.02",
                                    SliceKind.EDITOR_UI),
                            "compatibility/cubism/verification/cubism-5.2.03-ui-control-appearance.json",
                            controlAppearanceExpectation(
                                    "5.2.03",
                                    "cubism-5.2.03",
                                    ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                                    ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                                    "91796c8e80faf12f61043409b25d3403a9263b9391ed7a50275f9c8a38183215",
                                    "cubism-5.2.03-ui-control-appearance",
                                    "5.2.03"),
                            "compatibility/cubism/verification/cubism-5.2.03-editor-model.json",
                            new SliceExpectation(
                                    "cubism-5.2.03.editor-model.static",
                                    EditorModelVerificationManifest.ADAPTER_SLICE_ID,
                                    "5.2.03",
                                    "cubism-5.2.03",
                                    EditorModelVerificationManifest.cubism52Capabilities(),
                                    "Live2D_Cubism.jar",
                                    ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                                    ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                                    "6ca758d3ac0b3712feb57ed9a6501967e40c99b0e70a2111606e4be8ebb84154",
                                    1100,
                                    EditorModelVerificationManifest.cubism52Aliases(),
                                    EditorModelVerificationManifest.cubism52Aliases(),
                                    recordMethodAliases(
                                            "compatibility/cubism/verification/cubism-5.2.03-editor-model.json"),
                                    recordClassAliases(
                                            "compatibility/cubism/verification/cubism-5.2.03-editor-model.json"),
                                    "cubism-5.2.03-editor-model-read",
                                    Path.of(
                                            "compatibility/cubism/mapping-packs/draft/cubism-5.2.03-editor-model-read.json"),
                                    Path.of("compatibility/cubism/profiles/draft/cubism-5.2.03.json"),
                                    "5.2.03",
                                    SliceKind.EDITOR_MODEL),
                            "compatibility/cubism/verification/cubism-5.3.02-editor-model.json",
                            new SliceExpectation(
                                    EditorModelVerificationManifest.RECORD_5_3_02.verificationId(),
                                    EditorModelVerificationManifest.ADAPTER_SLICE_ID,
                                    EditorModelVerificationManifest.RECORD_5_3_02.cubismVersion(),
                                    EditorModelVerificationManifest.RECORD_5_3_02.profileId(),
                                    EditorModelVerificationManifest.cubism5302Capabilities(),
                                    "Live2D_Cubism.jar",
                                    EditorModelVerificationManifest.RECORD_5_3_02
                                            .artifact()
                                            .size(),
                                    EditorModelVerificationManifest.RECORD_5_3_02
                                            .artifact()
                                            .sha256(),
                                    EditorModelVerificationManifest.RECORD_5_3_02.recordSha256(),
                                    1120,
                                    EditorModelVerificationManifest.cubism5302Aliases(),
                                    EditorModelVerificationManifest.cubism5302Aliases(),
                                    recordMethodAliases(
                                            "compatibility/cubism/verification/cubism-5.3.02-editor-model.json"),
                                    recordClassAliases(
                                            "compatibility/cubism/verification/cubism-5.3.02-editor-model.json"),
                                    "cubism-5.3.02-editor-model-read",
                                    Path.of(
                                            "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-editor-model-read.json"),
                                    Path.of("compatibility/cubism/profiles/draft/cubism-5.3.02.json"),
                                    "5.3.02",
                                    SliceKind.EDITOR_MODEL),
                            "compatibility/cubism/verification/cubism-5.3.02-core-model-read.json",
                            coreExpectation(
                                    "5.3.02",
                                    "5.3.02",
                                    42_471L,
                                    "98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4",
                                    "6650108a950731fcc8d98210b40398a9d3c6fece8fda19b220bcdf58a09bd8da",
                                    82,
                                    "5.3.02"))))))))))))));

    /**
     * Registers one repository expectation per emitted exact-version record not already pinned by
     * the hand-maintained tables above. All fields are derived from the record itself; the only
     * literal pin is the record-file digest in {@link #EXPECTED_VERSIONED_RECORD_SHA256}, which
     * anchors the restated content.
     */
    private static Map<String, SliceExpectation> withVersionedRecords(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        try (Stream<Path> records = Files.list(RECORDS)) {
            for (Path recordPath : records.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                final String repositoryPath = repositoryPath(recordPath);
                if (!expectations.containsKey(repositoryPath)) {
                    addVersionedRecordExpectation(expectations, recordPath, repositoryPath);
                }
            }
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
        return Map.copyOf(expectations);
    }

    private static void addVersionedRecordExpectation(
            final Map<String, SliceExpectation> expectations, final Path recordPath, final String repositoryPath) {
        try {
            final JsonNode record = new ObjectMapper().readTree(recordPath.toFile());
            final String fileName = recordPath.getFileName().toString().replace(".json", "");
            final String cubismVersion = record.get("cubismVersion").asText();
            final String packId = fileName.endsWith("-editor-model") ? fileName + "-read" : fileName;
            final Path packPath = Path.of("compatibility/cubism/mapping-packs/draft/" + packId + ".json");
            if (!Files.isRegularFile(PROJECT_ROOT.resolve(packPath))) {
                throw new IllegalStateException("missing required DRAFT pack " + packPath);
            }
            final Path profilePath = Path.of("compatibility/cubism/profiles/draft/cubism-" + cubismVersion + ".json");
            final String recordSha256 = EXPECTED_VERSIONED_RECORD_SHA256.get(repositoryPath);
            if (recordSha256 == null) {
                throw new IllegalStateException("unpinned record digest " + repositoryPath);
            }
            final Set<String> aliases = recordAliases(repositoryPath);
            final Set<String> classAliases = recordClassAliases(repositoryPath);
            final SliceKind kind = fileName.contains("editor-model")
                    ? SliceKind.EDITOR_MODEL
                    : fileName.contains("project-workspace")
                            ? SliceKind.PROJECT_WORKSPACE
                            : fileName.contains("clipmask")
                                    ? SliceKind.CLIP_MASK
                                    : fileName.contains("core-model")
                                            ? SliceKind.CORE
                                            : fileName.contains("performance")
                                                    ? SliceKind.PERFORMANCE
                                                    : SliceKind.EDITOR_UI;
            expectations.put(
                    repositoryPath,
                    new SliceExpectation(
                            record.get("verificationId").asText(),
                            record.get("adapterSliceId").asText(),
                            cubismVersion,
                            "cubism-" + cubismVersion,
                            recordCapabilities(repositoryPath),
                            record.get("artifact").get("name").asText(),
                            record.get("artifact").get("size").asLong(),
                            record.get("artifact").get("sha256").asText(),
                            recordSha256,
                            aliases.size(),
                            aliases,
                            aliases,
                            recordMethodAliases(repositoryPath),
                            classAliases,
                            packId,
                            packPath,
                            profilePath,
                            cubismVersion,
                            kind));
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Map<String, SliceExpectation> withProtectedExports(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-protected-export.json",
                new SliceExpectation(
                        ProtectedExportVerificationManifest.RECORD_5_2_03.verificationId(),
                        ProtectedExportVerificationManifest.ADAPTER_SLICE_ID,
                        ProtectedExportVerificationManifest.RECORD_5_2_03.cubismVersion(),
                        ProtectedExportVerificationManifest.RECORD_5_2_03.profileId(),
                        ProtectedExportVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        ProtectedExportVerificationManifest.RECORD_5_2_03
                                .artifact()
                                .size(),
                        ProtectedExportVerificationManifest.RECORD_5_2_03
                                .artifact()
                                .sha256(),
                        ProtectedExportVerificationManifest.RECORD_5_2_03.recordSha256(),
                        204,
                        ProtectedExportVerificationManifest.cubism52Aliases(),
                        VerifiedProtectedExportHostOperations.CUBISM_5_2_REQUIRED_ALIASES,
                        VerifiedProtectedExportHostOperations.cubism52MethodAliasesUsed(),
                        VerifiedProtectedExportHostOperations.cubism52ClassAliasesUsed(),
                        "cubism-5.2.03-protected-export",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.2.03-protected-export.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.2.03.json"),
                        "5.2.03",
                        SliceKind.PROTECTED_EXPORT));
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-protected-export.json",
                new SliceExpectation(
                        ProtectedExportVerificationManifest.RECORD_5_3_02.verificationId(),
                        ProtectedExportVerificationManifest.ADAPTER_SLICE_ID,
                        ProtectedExportVerificationManifest.RECORD_5_3_02.cubismVersion(),
                        ProtectedExportVerificationManifest.RECORD_5_3_02.profileId(),
                        ProtectedExportVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        ProtectedExportVerificationManifest.RECORD_5_3_02
                                .artifact()
                                .size(),
                        ProtectedExportVerificationManifest.RECORD_5_3_02
                                .artifact()
                                .sha256(),
                        ProtectedExportVerificationManifest.RECORD_5_3_02.recordSha256(),
                        220,
                        ProtectedExportVerificationManifest.REQUIRED_ALIASES,
                        VerifiedProtectedExportHostOperations.REQUIRED_ALIASES,
                        VerifiedProtectedExportHostOperations.methodAliasesUsed(),
                        VerifiedProtectedExportHostOperations.classAliasesUsed(),
                        "cubism-5.3.02-protected-export",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.3.02-protected-export.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.3.02.json"),
                        "5.3.02",
                        SliceKind.PROTECTED_EXPORT));
        return Map.copyOf(expectations);
    }

    private static Map<String, SliceExpectation> with5303(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        for (String fileName : new String[] {
            "cubism-5.3.03-autobackup.json",
            "cubism-5.3.03-clipmask.json",
            "cubism-5.3.03-editor-model.json",
            "cubism-5.3.03-performance-render-scene.json",
            "cubism-5.3.03-protected-export.json",
            "cubism-5.3.03-project-workspace.json",
            "cubism-5.3.03-ui-bounding-box-overlay.json",
            "cubism-5.3.03-ui-control-appearance.json",
            "cubism-5.3.03-ui-embedded-panel.json",
            "cubism-5.3.03-ui-main-toolbar.json",
            "cubism-5.3.03-ui-status-bar.json",
            "cubism-5.3.03-ui-top-menu.json",
            "cubism-5.3.03-workspace-control.json"
        }) {
            add5303RecordExpectation(expectations, Path.of("compatibility/cubism/verification/" + fileName));
        }
        return Map.copyOf(expectations);
    }

    private static void add5303RecordExpectation(
            final Map<String, SliceExpectation> expectations, final Path recordPath) {
        final Path absoluteRecord = PROJECT_ROOT.resolve(recordPath);
        final String repositoryPath = recordPath.toString().replace('\\', '/');
        try {
            final JsonNode record = new ObjectMapper().readTree(absoluteRecord.toFile());
            final String fileName = recordPath.getFileName().toString().replace(".json", "");
            final String packId = packIdFor5303(fileName);
            final Path packPath = Path.of("compatibility/cubism/mapping-packs/draft/" + packId + ".json");
            if (!Files.isRegularFile(PROJECT_ROOT.resolve(packPath))) {
                throw new IllegalStateException("missing required 5.3.03 DRAFT pack " + packPath);
            }
            final Set<String> aliases = recordAliases(repositoryPath);
            final Set<String> classAliases = recordClassAliases(repositoryPath);
            final SliceKind kind = fileName.contains("editor-model")
                    ? SliceKind.EDITOR_MODEL
                    : fileName.contains("project-workspace")
                            ? SliceKind.PROJECT_WORKSPACE
                            : fileName.contains("clipmask")
                                    ? SliceKind.CLIP_MASK
                                    : fileName.contains("performance")
                                            ? SliceKind.PERFORMANCE
                                            : fileName.contains("protected-export")
                                                    ? SliceKind.PROTECTED_EXPORT
                                                    : SliceKind.EDITOR_UI;
            expectations.put(
                    repositoryPath,
                    new SliceExpectation(
                            record.get("verificationId").asText(),
                            record.get("adapterSliceId").asText(),
                            "5.3.03",
                            "cubism-5.3.03",
                            recordCapabilities(repositoryPath),
                            "Live2D_Cubism.jar",
                            42_010_633L,
                            "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166",
                            expected5303RecordSha(fileName),
                            aliases.size(),
                            aliases,
                            aliases,
                            recordMethodAliases(repositoryPath),
                            classAliases,
                            packId,
                            packPath,
                            Path.of("compatibility/cubism/profiles/draft/cubism-5.3.03.json"),
                            "5.3.03",
                            kind));
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String expected5303RecordSha(final String fileName) {
        return switch (fileName) {
            case "cubism-5.3.03-autobackup" -> "639036d9d0bc35a480ef18521efc43eec29f26a95c4eea7b136f52e40cf0d74d";
            case "cubism-5.3.03-clipmask" -> "f4702a5914c8fa8f6e8b266c675bc0c9e21b2667364ddca05b313f10b1351459";
            case "cubism-5.3.03-editor-model" -> "f38de80eea22fc5fc64e82caffe7e50edbc18249978874f160470df000a15849";
            case "cubism-5.3.03-performance-render-scene" ->
                "1f46c24551b7b5ccc63bb379a4498d5b3166fa20916499ca9b5c8c52da307ba5";
            case "cubism-5.3.03-protected-export" -> "de6066d86686cd3445b921086e7747167cf30b2fa89eb8efe49da064242d99b7";
            case "cubism-5.3.03-project-workspace" ->
                "401e121ca6d06634b04f59737660c2eec95fee37f8603b5eacd86fd74e7c7fa9";
            case "cubism-5.3.03-ui-bounding-box-overlay" ->
                "fd67451595cbf68ca3084504c730daee3b110577e67852680db64b3a6f81e000";
            case "cubism-5.3.03-ui-control-appearance" ->
                "6c771b8564af0569ed1c47064f13db20a0da5b8ef3dd1d90d06905311c58f710";
            case "cubism-5.3.03-ui-embedded-panel" ->
                "efd21e78301e09cb5cdf4d4d80fdbaa78c6f676f8bb813ef28af41a2656dca55";
            case "cubism-5.3.03-ui-main-toolbar" -> "cee7eca72cd4abf180d0e01905381907c8ae35056d68dc4b2f7911d2e2d4fee4";
            case "cubism-5.3.03-ui-status-bar" -> "f74152ef76ac4f88daf22ae3670aa8296757f18818f98d8763deea913cd3a1f2";
            case "cubism-5.3.03-ui-top-menu" -> "8468ba23f43f3176cd20b92851348c719b1b8811b90c5af055fd26bf10f0bfea";
            case "cubism-5.3.03-workspace-control" ->
                "1a1be264fe64c6dc3e2f1ec10cb854a475540ebf1270b93e6d50e01ae6b801fc";
            default -> throw new IllegalArgumentException("unregistered 5.3.03 record " + fileName);
        };
    }

    private static String packIdFor5303(final String fileName) {
        return fileName.equals("cubism-5.3.03-editor-model") ? "cubism-5.3.03-editor-model-read" : fileName;
    }

    private static Set<String> recordAliases(final String recordPath) {
        return recordStringSet(recordPath, "selectors", "alias");
    }

    private static Set<String> recordCapabilities(final String recordPath) {
        try {
            final JsonNode root =
                    new ObjectMapper().readTree(PROJECT_ROOT.resolve(recordPath).toFile());
            return asStringSet(root.get("capabilityIds"));
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read reviewed record " + recordPath, failure);
        }
    }

    private static Set<String> recordStringSet(
            final String recordPath, final String arrayField, final String valueField) {
        try {
            final JsonNode root =
                    new ObjectMapper().readTree(PROJECT_ROOT.resolve(recordPath).toFile());
            final Set<String> values = new HashSet<>();
            root.get(arrayField).forEach(node -> values.add(node.get(valueField).asText()));
            return Set.copyOf(values);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read reviewed record " + recordPath, failure);
        }
    }

    private static Map<String, SliceExpectation> withWorkspaceControl(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-workspace-control.json",
                workspaceControlExpectation(
                        "5.2.03",
                        "5.2.03",
                        ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                        ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                        "1acf44eab99b480b1981c3c14f0229d2f0c5cfefdcc739fa831100a42496be94",
                        "m.workspace-5.2.03.control.static",
                        "adapter.workspace.control.v5_2",
                        "5.2.03"));
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-workspace-control.json",
                workspaceControlExpectation(
                        "5.3.02",
                        "5.3.02",
                        ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                        ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
                        "ac52277ae32f617d7aeced4845f15adf91c414b9e206d935a7a904f62e5ecf47",
                        "m.workspace-5.3.02.control.static",
                        "adapter.workspace.control.v5_3",
                        "5.3.02"));
        return Map.copyOf(expectations);
    }

    private static SliceExpectation workspaceControlExpectation(
            String profile,
            String version,
            long size,
            String artifactSha,
            String recordSha,
            String verificationId,
            String sliceId,
            String range) {
        final Set<String> aliases = WorkspaceControlVerificationManifest.REQUIRED_ALIASES;
        return new SliceExpectation(
                verificationId,
                sliceId,
                version,
                "cubism-" + profile,
                Set.of(WorkspaceControlVerificationManifest.CAPABILITY_ID),
                "Live2D_Cubism.jar",
                size,
                artifactSha,
                recordSha,
                aliases.size(),
                aliases,
                aliases,
                difference(aliases, Set.of("workspace.app.class")),
                Set.of("workspace.app.class"),
                "cubism-" + profile + "-workspace-control",
                Path.of("compatibility/cubism/mapping-packs/draft/cubism-" + profile + "-workspace-control.json"),
                Path.of("compatibility/cubism/profiles/draft/cubism-" + profile + ".json"),
                range,
                SliceKind.EDITOR_UI);
    }

    private static Map<String, SliceExpectation> withEmbeddedPanels(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-ui-embedded-panel.json",
                new SliceExpectation(
                        "cubism-5.2.03.ui-embedded-panel.static",
                        EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
                        "5.2.03",
                        "cubism-5.2.03",
                        EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                        ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                        "d4aa6e2e20feac7c0f9af1aea365c44ac00d1a913e68085c9f80a89d9cfb70ef",
                        78,
                        EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                        EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                        embeddedPanelMethodAliases(),
                        difference(EmbeddedPanelVerificationManifest.REQUIRED_ALIASES, embeddedPanelMethodAliases()),
                        "cubism-5.2.03-ui-embedded-panel",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.2.03-ui-embedded-panel.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.2.03.json"),
                        "5.2.03",
                        SliceKind.EDITOR_UI));
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-ui-embedded-panel.json",
                new SliceExpectation(
                        EmbeddedPanelVerificationManifest.RECORD_5_3_02.verificationId(),
                        EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
                        EmbeddedPanelVerificationManifest.RECORD_5_3_02.cubismVersion(),
                        EmbeddedPanelVerificationManifest.RECORD_5_3_02.profileId(),
                        EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        EmbeddedPanelVerificationManifest.RECORD_5_3_02
                                .artifact()
                                .size(),
                        EmbeddedPanelVerificationManifest.RECORD_5_3_02
                                .artifact()
                                .sha256(),
                        EmbeddedPanelVerificationManifest.RECORD_5_3_02.recordSha256(),
                        78,
                        EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                        EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                        embeddedPanelMethodAliases(),
                        difference(EmbeddedPanelVerificationManifest.REQUIRED_ALIASES, embeddedPanelMethodAliases()),
                        "cubism-5.3.02-ui-embedded-panel",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.3.02-ui-embedded-panel.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.3.02.json"),
                        "5.3.02",
                        SliceKind.EDITOR_UI));
        return Map.copyOf(expectations);
    }

    /**
     * Method-kind aliases declared by a reviewed record.
     *
     * <p>Editor-model has no {@code Verified*HostOperations.methodAliasesUsed()} to compare
     * against: its aliases are inline literals across 23 access classes. Whether the
     * implementation actually invokes them is checked by
     * {@code scripts/test/check_editor_model_aliases.py}, which derives the implementation side
     * by scanning. This expectation therefore only restates the record's own method/class split,
     * which is what the surrounding pack-binding assertions need.</p>
     */
    private static Set<String> recordMethodAliases(final String recordPath) {
        return recordAliasesOfKind(recordPath, false);
    }

    /** Class-kind aliases declared by a reviewed record; see {@link #recordMethodAliases}. */
    private static Set<String> recordClassAliases(final String recordPath) {
        return recordAliasesOfKind(recordPath, true);
    }

    private static Set<String> recordAliasesOfKind(final String recordPath, final boolean classes) {
        try {
            final JsonNode root =
                    new ObjectMapper().readTree(PROJECT_ROOT.resolve(recordPath).toFile());
            final Set<String> aliases = new HashSet<>();
            for (JsonNode selector : root.get("selectors")) {
                final String kind = selector.get("kind").asText();
                final boolean isClass = "class".equals(kind);
                if (isClass == classes && !"inherits".equals(kind)) {
                    aliases.add(selector.get("alias").asText());
                }
            }
            return Set.copyOf(aliases);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read reviewed record " + recordPath, failure);
        }
    }

    private static Map<String, SliceExpectation> withPerformance(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-performance-render-scene.json",
                performanceExpectation(
                        "cubism-5.2.03.performance.render-scene.static",
                        "5.2.03",
                        "cubism-5.2.03",
                        ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                        ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                        "399c09052ddb715f1d3ca257d84d41d851b74da3aba18a84f0fefc5956fd4cab",
                        Set.of(
                                "cubism.performance.modeling-pre-render-update",
                                "cubism.performance.reinit-model-instance",
                                "cubism.performance.render-scene",
                                "cubism.performance.update-model-instances"),
                        "cubism-5.2.03-performance-render-scene",
                        "5.2.03"));
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-performance-render-scene.json",
                performanceExpectation(
                        "cubism-5.3.02.performance.render-scene.static",
                        "5.3.02",
                        "cubism-5.3.02",
                        ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                        ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
                        "cf401975fa0e033d7e093a54117a4f78ff5ff218bb9b7c4f7eae5bb7e8f57a92",
                        Set.of(
                                "cubism.performance.render-scene",
                                "cubism.performance.modeling-pre-render-update",
                                "cubism.performance.render-system",
                                "cubism.performance.scene-traversal",
                                "cubism.performance.renderer-dispatch",
                                "cubism.performance.update-model-instances",
                                "cubism.performance.reinit-model-instance"),
                        "cubism-5.3.02-performance-render-scene",
                        "5.3.02"));
        return Map.copyOf(expectations);
    }

    private static SliceExpectation performanceExpectation(
            final String verificationId,
            final String cubismVersion,
            final String profileId,
            final long artifactSize,
            final String artifactSha256,
            final String recordSha256,
            final Set<String> aliases,
            final String packId,
            final String expectedCubismVersion) {
        return new SliceExpectation(
                verificationId,
                "adapter.cubism.performance.fps-hook",
                cubismVersion,
                profileId,
                Set.of("turboism.performance.stats.read"),
                "Live2D_Cubism.jar",
                artifactSize,
                artifactSha256,
                recordSha256,
                aliases.size(),
                aliases,
                aliases,
                aliases,
                Set.of(),
                packId,
                Path.of("compatibility/cubism/mapping-packs/draft/" + packId + ".json"),
                Path.of("compatibility/cubism/profiles/draft/" + profileId + ".json"),
                expectedCubismVersion,
                SliceKind.PERFORMANCE);
    }

    private static Map<String, SliceExpectation> withClipMask52(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-clipmask.json",
                new SliceExpectation(
                        ClipMaskVerificationManifest.RECORD_5_2_03.verificationId(),
                        ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
                        ClipMaskVerificationManifest.RECORD_5_2_03.cubismVersion(),
                        ClipMaskVerificationManifest.RECORD_5_2_03.profileId(),
                        ClipMaskVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        ClipMaskVerificationManifest.RECORD_5_2_03.artifact().size(),
                        ClipMaskVerificationManifest.RECORD_5_2_03.artifact().sha256(),
                        ClipMaskVerificationManifest.RECORD_5_2_03.recordSha256(),
                        16,
                        ClipMaskVerificationManifest.REQUIRED_ALIASES,
                        VerifiedClipMaskHostOperations.REQUIRED_ALIASES,
                        VerifiedClipMaskHostOperations.methodAliasesUsed(),
                        VerifiedClipMaskHostOperations.classAliasesUsed(),
                        "cubism-5.2.03-clipmask",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.2.03-clipmask.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.2.03.json"),
                        "5.2.03",
                        SliceKind.CLIP_MASK));
        return Map.copyOf(expectations);
    }

    private static Map<String, SliceExpectation> withAutoBackup(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-autobackup.json",
                autoBackupExpectation(
                        AutoBackupVerificationManifest.VERIFICATION_ID_5_3_02,
                        AutoBackupVerificationManifest.CUBISM_VERSION_5_3_02,
                        AutoBackupVerificationManifest.PROFILE_ID_5_3_02,
                        AutoBackupVerificationManifest.ARTIFACT_SIZE_5_3_02,
                        AutoBackupVerificationManifest.ARTIFACT_SHA256_5_3_02,
                        AutoBackupVerificationManifest.RECORD_SHA256_5_3_02,
                        "cubism-5.3.02-autobackup",
                        "compatibility/cubism/profiles/draft/cubism-5.3.02.json",
                        "5.3.02"));
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-autobackup.json",
                autoBackupExpectation(
                        AutoBackupVerificationManifest.VERIFICATION_ID_5_2_03,
                        AutoBackupVerificationManifest.CUBISM_VERSION_5_2_03,
                        AutoBackupVerificationManifest.PROFILE_ID_5_2_03,
                        AutoBackupVerificationManifest.ARTIFACT_SIZE_5_2_03,
                        AutoBackupVerificationManifest.ARTIFACT_SHA256_5_2_03,
                        AutoBackupVerificationManifest.RECORD_SHA256_5_2_03,
                        "cubism-5.2.03-autobackup",
                        "compatibility/cubism/profiles/draft/cubism-5.2.03.json",
                        "5.2.03"));
        return Map.copyOf(expectations);
    }

    private static SliceExpectation autoBackupExpectation(
            final String verificationId,
            final String cubismVersion,
            final String profileId,
            final long artifactSize,
            final String artifactSha256,
            final String recordSha256,
            final String packId,
            final String profilePath,
            final String expectedCubismVersion) {
        final Set<String> aliases = AutoBackupVerificationManifest.REQUIRED_ALIASES;
        return new SliceExpectation(
                verificationId,
                AutoBackupVerificationManifest.ADAPTER_SLICE_ID,
                cubismVersion,
                profileId,
                AutoBackupVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                artifactSize,
                artifactSha256,
                recordSha256,
                aliases.size(),
                aliases,
                aliases,
                difference(
                        aliases,
                        Set.of(
                                "cubism.auto-backup.manager.class",
                                "cubism.auto-backup.app-controller.class",
                                "cubism.auto-backup.complete-pack.class",
                                "cubism.auto-backup.file-content.class")),
                Set.of(
                        "cubism.auto-backup.manager.class",
                        "cubism.auto-backup.app-controller.class",
                        "cubism.auto-backup.complete-pack.class",
                        "cubism.auto-backup.file-content.class"),
                packId,
                Path.of("compatibility/cubism/mapping-packs/draft/" + packId + ".json"),
                Path.of(profilePath),
                expectedCubismVersion,
                SliceKind.EDITOR_UI);
    }

    private static Map<String, SliceExpectation> withStatusBar(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-ui-status-bar.json",
                new SliceExpectation(
                        "cubism-5.2.03.ui-status-bar.static",
                        StatusBarVerificationManifest.ADAPTER_SLICE_ID,
                        "5.2.03",
                        "cubism-5.2.03",
                        StatusBarVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                        ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                        StatusBarVerificationManifest.RECORD_5_2_03.recordSha256(),
                        27,
                        StatusBarVerificationManifest.REQUIRED_ALIASES,
                        StatusBarVerificationManifest.REQUIRED_ALIASES,
                        statusBarMethodAliases(),
                        difference(StatusBarVerificationManifest.REQUIRED_ALIASES, statusBarMethodAliases()),
                        "cubism-5.2.03-ui-status-bar",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.2.03-ui-status-bar.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.2.03.json"),
                        "5.2.03",
                        SliceKind.EDITOR_UI));
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-ui-status-bar.json",
                new SliceExpectation(
                        StatusBarVerificationManifest.RECORD_5_3_02.verificationId(),
                        StatusBarVerificationManifest.ADAPTER_SLICE_ID,
                        StatusBarVerificationManifest.RECORD_5_3_02.cubismVersion(),
                        StatusBarVerificationManifest.RECORD_5_3_02.profileId(),
                        StatusBarVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        StatusBarVerificationManifest.RECORD_5_3_02.artifact().size(),
                        StatusBarVerificationManifest.RECORD_5_3_02.artifact().sha256(),
                        StatusBarVerificationManifest.RECORD_5_3_02.recordSha256(),
                        27,
                        StatusBarVerificationManifest.REQUIRED_ALIASES,
                        StatusBarVerificationManifest.REQUIRED_ALIASES,
                        statusBarMethodAliases(),
                        difference(StatusBarVerificationManifest.REQUIRED_ALIASES, statusBarMethodAliases()),
                        "cubism-5.3.02-ui-status-bar",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.3.02-ui-status-bar.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.3.02.json"),
                        "5.3.02",
                        SliceKind.EDITOR_UI));
        return Map.copyOf(expectations);
    }

    private static Set<String> statusBarMethodAliases() {
        return Set.of(
                "cubism.ui-status-bar.app-controller.instance",
                "cubism.ui-status-bar.app-controller.main-frame",
                "cubism.ui-canvas-hint.app-controller.current-view-context",
                "cubism.ui-canvas-hint.view-context.show-hint",
                "cubism.ui-canvas-hint.view-context.show-hint-with-action",
                "cubism.ui-canvas-hint.position.create",
                "cubism.ui-status-bar.main-frame-controller.frame",
                "cubism.ui-status-bar.frame.content-pane",
                "cubism.ui-status-bar.widget.set-name",
                "cubism.ui-status-bar.widget.set-tooltip",
                "cubism.ui-status-bar.widget.revalidate",
                "cubism.ui-status-bar.widget.repaint",
                "cubism.ui-status-bar.container.children",
                "cubism.ui-status-bar.container.add",
                "cubism.ui-status-bar.container.remove",
                "cubism.ui-status-bar.label.text",
                "cubism.ui-status-bar.label.set-text",
                "cubism.ui-status-bar.label.create");
    }

    private static Map<String, SliceExpectation> withTopMenus(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-ui-top-menu.json",
                new SliceExpectation(
                        "cubism-5.2.03.ui-top-menu.static",
                        TopMenuVerificationManifest.ADAPTER_SLICE_ID,
                        "5.2.03",
                        "cubism-5.2.03",
                        TopMenuVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                        ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                        "365bcaa24c0df8aad0e9ae28d4ef7cb72d05780f87d720c8181145784e6e042b",
                        82,
                        TopMenuVerificationManifest.REQUIRED_ALIASES,
                        TopMenuVerificationManifest.REQUIRED_ALIASES,
                        topMenuMethodAliases(),
                        difference(TopMenuVerificationManifest.REQUIRED_ALIASES, topMenuMethodAliases()),
                        "cubism-5.2.03-ui-top-menu",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.2.03-ui-top-menu.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.2.03.json"),
                        "5.2.03",
                        SliceKind.EDITOR_UI));
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-ui-top-menu.json",
                new SliceExpectation(
                        TopMenuVerificationManifest.RECORD_5_3_02.verificationId(),
                        TopMenuVerificationManifest.ADAPTER_SLICE_ID,
                        TopMenuVerificationManifest.RECORD_5_3_02.cubismVersion(),
                        TopMenuVerificationManifest.RECORD_5_3_02.profileId(),
                        TopMenuVerificationManifest.CAPABILITY_IDS,
                        "Live2D_Cubism.jar",
                        TopMenuVerificationManifest.RECORD_5_3_02.artifact().size(),
                        TopMenuVerificationManifest.RECORD_5_3_02.artifact().sha256(),
                        TopMenuVerificationManifest.RECORD_5_3_02.recordSha256(),
                        82,
                        TopMenuVerificationManifest.REQUIRED_ALIASES,
                        TopMenuVerificationManifest.REQUIRED_ALIASES,
                        topMenuMethodAliases(),
                        difference(TopMenuVerificationManifest.REQUIRED_ALIASES, topMenuMethodAliases()),
                        "cubism-5.3.02-ui-top-menu",
                        Path.of("compatibility/cubism/mapping-packs/draft/cubism-5.3.02-ui-top-menu.json"),
                        Path.of("compatibility/cubism/profiles/draft/cubism-5.3.02.json"),
                        "5.3.02",
                        SliceKind.EDITOR_UI));
        return Map.copyOf(expectations);
    }

    private static Map<String, SliceExpectation> withControlAppearance53(final Map<String, SliceExpectation> base) {
        final Map<String, SliceExpectation> expectations = new LinkedHashMap<>(base);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-ui-control-appearance.json",
                controlAppearanceExpectation(
                        "5.3.02",
                        "cubism-5.3.02",
                        ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                        ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
                        "70f4979093b0d93cbaa9abb24f769b667c62956ee44a67226fa65db185976059",
                        "cubism-5.3.02-ui-control-appearance",
                        "5.3.02"));
        return Map.copyOf(expectations);
    }

    private static SliceExpectation controlAppearanceExpectation(
            final String version,
            final String profile,
            final long artifactSize,
            final String artifactSha256,
            final String recordSha256,
            final String packId,
            final String expectedCubismVersion) {
        // Every alias the reviewed record declares with kind=class, listed rather than derived
        // so the expectation can disagree with the record.  was missing
        // while the record has always carried it.
        final Set<String> classAliases = Set.of(
                "cubism.ui-control-appearance.art-mesh.source-class",
                "cubism.ui-control-appearance.deformer-source.class",
                "cubism.ui-control-appearance.deformer-control.outer-class",
                "cubism.ui-control-appearance.deformer-control.tree-class",
                "cubism.ui-control-appearance.parameter.single-class",
                "cubism.ui-control-appearance.parameter.double-class",
                "cubism.ui-control-appearance.parameter.folder-class",
                "cubism.ui-control-appearance.parameter.source-class",
                "cubism.ui-control-appearance.parameter.folder-source-class",
                "cubism.ui-control-appearance.parameter.label-class",
                "cubism.ui-control-appearance.part.node-class",
                "cubism.ui-control-appearance.part.source-class");
        return new SliceExpectation(
                "cubism-" + version + ".ui-control-appearance.static",
                ControlAppearanceVerificationManifest.ADAPTER_SLICE_ID,
                version,
                profile,
                ControlAppearanceVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                artifactSize,
                artifactSha256,
                recordSha256,
                40,
                ControlAppearanceVerificationManifest.REQUIRED_ALIASES,
                ControlAppearanceVerificationManifest.REQUIRED_ALIASES,
                difference(ControlAppearanceVerificationManifest.REQUIRED_ALIASES, classAliases),
                classAliases,
                packId,
                Path.of("compatibility/cubism/mapping-packs/draft/" + packId + ".json"),
                Path.of("compatibility/cubism/profiles/draft/" + profile + ".json"),
                expectedCubismVersion,
                SliceKind.EDITOR_UI);
    }

    private static Map<String, SliceExpectation> withBoundingBoxOverlays(final Map<String, SliceExpectation> existing) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
                "compatibility/cubism/verification/cubism-5.2.03-ui-bounding-box-overlay.json",
                boundingBoxOverlayExpectation(
                        "cubism-5.2.03.ui-bounding-box-overlay.static",
                        "5.2.03",
                        "cubism-5.2.03",
                        ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                        ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                        "d11b021baf4eb38179924540880179ee8d0569da8d674643072cfbd53cc0611c",
                        "cubism-5.2.03-ui-bounding-box-overlay",
                        "5.2.03"));
        expectations.put(
                "compatibility/cubism/verification/cubism-5.3.02-ui-bounding-box-overlay.json",
                boundingBoxOverlayExpectation(
                        "cubism-5.3.02.ui-bounding-box-overlay.static",
                        "5.3.02",
                        "cubism-5.3.02",
                        ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                        ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
                        "743851942ba762ae69c6e1da01274f6605be2153c0081249ed81095ef84321a6",
                        "cubism-5.3.02-ui-bounding-box-overlay",
                        "5.3.02"));
        return Map.copyOf(expectations);
    }

    private static SliceExpectation boundingBoxOverlayExpectation(
            final String verificationId,
            final String cubismVersion,
            final String profileId,
            final long artifactSize,
            final String artifactSha256,
            final String recordSha256,
            final String packId,
            final String expectedCubismVersion) {
        return new SliceExpectation(
                verificationId,
                BoundingBoxOverlayButtonVerificationManifest.ADAPTER_SLICE_ID,
                cubismVersion,
                profileId,
                BoundingBoxOverlayButtonVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                artifactSize,
                artifactSha256,
                recordSha256,
                12,
                BoundingBoxOverlayButtonVerificationManifest.REQUIRED_ALIASES,
                BoundingBoxOverlayButtonVerificationManifest.REQUIRED_ALIASES,
                BoundingBoxOverlayButtonVerificationManifest.REQUIRED_ALIASES,
                Set.of(),
                packId,
                Path.of("compatibility/cubism/mapping-packs/draft/" + packId + ".json"),
                Path.of("compatibility/cubism/profiles/draft/" + profileId + ".json"),
                expectedCubismVersion,
                SliceKind.EDITOR_UI);
    }

    private static Set<String> topMenuMethodAliases() {
        return Set.of(
                "cubism.ui-top-menu.app-controller.instance",
                "cubism.ui-top-menu.app-controller.main-frame",
                "cubism.ui-top-menu.main-frame.window",
                "cubism.ui-top-menu.menu.add",
                "cubism.ui-top-menu.menu-bar.add",
                "cubism.ui-top-menu.menu-bar.menus",
                "cubism.ui-top-menu.menu-bar.swing",
                "cubism.ui-top-menu.menu.create",
                "cubism.ui-top-menu.menu-item.create",
                "cubism.ui-top-menu.menu.swing",
                "cubism.editor-command.canvas.begin-edit",
                "cubism.editor-command.canvas.canvas",
                "cubism.editor-command.canvas.companion",
                "cubism.editor-command.canvas.complete-pack",
                "cubism.editor-command.canvas.current-view-context",
                "cubism.editor-command.canvas.doc-size",
                "cubism.editor-command.canvas.edit-mode",
                "cubism.editor-command.canvas.end-edit-default",
                "cubism.editor-command.canvas.group-add",
                "cubism.editor-command.canvas.handler",
                "cubism.editor-command.canvas.is-editing",
                "cubism.editor-command.canvas.mark-dirty",
                "cubism.editor-command.canvas.model",
                "cubism.editor-command.canvas.model-source",
                "cubism.editor-command.canvas.modeling-doc",
                "cubism.editor-command.canvas.notify-size",
                "cubism.editor-command.canvas.pixel-height",
                "cubism.editor-command.canvas.pixel-width",
                "cubism.editor-command.canvas.scale-with-anchor",
                "cubism.editor-command.canvas.set-pixel-height",
                "cubism.editor-command.canvas.set-pixel-width",
                "cubism.editor-command.canvas.simple-undo",
                "cubism.editor-command.canvas.size-height",
                "cubism.editor-command.canvas.size-width",
                "cubism.editor-command.canvas.undo",
                "cubism.editor-command.canvas.undo-manager",
                "cubism.editor-command.canvas.undo-pos",
                "cubism.editor-command.canvas.vector2",
                "cubism.editor-command.canvas.vector2-zero",
                "cubism.editor-command.config.instance",
                "cubism.editor-command.config.read",
                "cubism.editor-command.config.write",
                "cubism.editor-command.external-app.companion",
                "cubism.editor-command.external-app.connected",
                "cubism.editor-command.external-app.get-port",
                "cubism.editor-command.external-app.get-remote",
                "cubism.editor-command.external-app.instance",
                "cubism.editor-command.external-app.manager",
                "cubism.editor-command.external-app.set-port",
                "cubism.editor-command.external-app.set-remote",
                "cubism.editor-command.external-app.start",
                "cubism.editor-command.external-app.stop",
                "cubism.editor-command.file.save-model",
                "cubism.editor-command.file.save-scene",
                "cubism.editor-command.file.scene-content",
                "cubism.editor-command.grid.all-view-contexts",
                "cubism.editor-command.grid.color-create",
                "cubism.editor-command.grid.developer-setting",
                "cubism.editor-command.grid.entity",
                "cubism.editor-command.grid.entity-from-draw",
                "cubism.editor-command.grid.get-bold",
                "cubism.editor-command.grid.get-color",
                "cubism.editor-command.grid.get-jcolor",
                "cubism.editor-command.grid.get-spacing",
                "cubism.editor-command.grid.modeling-draw",
                "cubism.editor-command.grid.repaint-default",
                "cubism.editor-command.grid.set-color",
                "cubism.editor-command.grid.set-reset",
                "cubism.editor-command.grid.set-spacing",
                "cubism.editor-command.grid.update-manager",
                "cubism.editor-command.resize.guard",
                "cubism.editor-command.resize.guard-active",
                "cubism.editor-command.resize.guard-current",
                "cubism.editor-command.resize.scale-model",
                "cubism.ui-top-menu.widget.name",
                "cubism.ui-top-menu.widget.repaint",
                "cubism.ui-top-menu.widget.revalidate",
                "cubism.ui-top-menu.widget.set-name",
                "cubism.ui-top-menu.window.menu-bar");
    }

    private static Set<String> embeddedPanelMethodAliases() {
        return Set.of(
                "cubism.ui-panel.app-controller.instance",
                "cubism.ui-panel.app-controller.main-frame",
                "cubism.ui-panel.app-controller.repaint",
                "cubism.ui-panel.main-frame.dock-manager",
                "cubism.ui-panel.dock.palette-manager",
                "cubism.ui-panel.dock.set-palette-visible",
                "cubism.ui-panel.dock.update-window-menu",
                "cubism.ui-panel.palette-manager.get",
                "cubism.ui-panel.palette-manager.add",
                "cubism.ui-panel.palette-manager.close",
                "cubism.ui-panel.palette-manager.current-workspace",
                "cubism.ui-panel.workspace.activate",
                "cubism.ui-panel.workspace.palette-box-for",
                "cubism.ui-panel.palette-box.remove-tab",
                "cubism.ui-panel.palette-manager.remove-update",
                "cubism.ui-panel.palette-manager.main-frame-window",
                "cubism.ui-panel.palette-manager.verify-cleanup",
                "cubism.ui-panel.palette-manager.fire-state",
                "cubism.ui-panel.workspace.add-palette-frame",
                "cubism.ui-panel.workspace.remove-palette-frame",
                "cubism.ui-panel.workspace.first-palette-box",
                "cubism.ui-panel.palette-box.create",
                "cubism.ui-panel.palette-box.add-tab",
                "cubism.ui-panel.palette-box.set-selected",
                "cubism.ui-panel.palette-box.palettes",
                "cubism.ui-panel.palette-box.tab-panel",
                "cubism.ui-panel.tab-panel.entries",
                "cubism.ui-panel.tab-entry.palette",
                "cubism.ui-panel.tab-entry.button",
                "cubism.ui-panel.widget.jcomponent",
                "cubism.ui-panel.palette-frame.create",
                "cubism.ui-panel.palette-frame.root",
                "cubism.ui-panel.palette-frame.window",
                "cubism.ui-panel.palette-frame.dispose",
                "cubism.ui-panel.palette-frame.raw-disposed",
                "cubism.ui-panel.floating-tab-close.operation",
                "cubism.ui-panel.floating-tab-close.palette-field",
                "cubism.ui-panel.workspace.root-container",
                "cubism.ui-panel.root.component",
                "cubism.ui-panel.split.contents",
                "cubism.ui-panel.split.remove",
                "cubism.ui-panel.component.palette-count",
                "cubism.ui-panel.root.set-component",
                "cubism.ui-panel.window.set-visible",
                "cubism.ui-panel.palette-id.create",
                "cubism.ui-panel.palette.create",
                "cubism.ui-panel.palette.set-panel",
                "cubism.ui-panel.swing-container.create",
                "cubism.ui-panel.main-frame.window",
                "cubism.ui-panel.window.menu-bar",
                "cubism.ui-panel.menu-bar.menus",
                "cubism.ui-panel.widget.name",
                "cubism.ui-panel.widget.set-name",
                "cubism.ui-panel.widget.revalidate",
                "cubism.ui-panel.widget.repaint",
                "cubism.ui-panel.menu.items",
                "cubism.ui-panel.menu.add",
                "cubism.ui-panel.menu.swing",
                "cubism.ui-panel.menu-item.create",
                "cubism.ui-panel.menu-item.check.create",
                "cubism.ui-panel.menu-item.swing",
                "cubism.ui-panel.menu-item.is-selected",
                "cubism.ui-panel.dock.main-frame-ctrl",
                "cubism.ui-panel.main-frame.palette-menu-map",
                "cubism.ui-panel.palette.id",
                "cubism.ui-panel.dock-tab-popup.operation",
                "cubism.ui-panel.dock-tab-popup.palette-field",
                "cubism.ui-panel.dock-tab-popup.menu-append");
    }

    private static Set<String> mainToolbarMethodAliases() {
        return Set.of(
                "cubism.ui-main-toolbar.app-controller.instance",
                "cubism.ui-main-toolbar.app-controller.main-frame",
                "cubism.ui-main-toolbar.main-frame.view",
                "cubism.ui-main-toolbar.main-frame-view.home-button",
                "cubism.ui-main-toolbar.widget.parent",
                "cubism.ui-main-toolbar.widget.name",
                "cubism.ui-main-toolbar.widget.set-name",
                "cubism.ui-main-toolbar.widget.set-tooltip",
                "cubism.ui-main-toolbar.widget.set-pref-width",
                "cubism.ui-main-toolbar.widget.set-pref-height",
                "cubism.ui-main-toolbar.widget.revalidate",
                "cubism.ui-main-toolbar.widget.repaint",
                "cubism.ui-main-toolbar.container.children",
                "cubism.ui-main-toolbar.container.add",
                "cubism.ui-main-toolbar.container.remove",
                "cubism.ui-main-toolbar.icon-button.create",
                "cubism.ui-main-toolbar.icon-button.set-rollover-icon",
                "cubism.ui-main-toolbar.icon.create",
                "cubism.ui-main-toolbar.main-frame-view.main-container",
                "cubism.ui-main-toolbar.vbox.create",
                "cubism.ui-main-toolbar.widget.jcomponent",
                "cubism.ui-main-toolbar.mesh-tool-mode.instance",
                "cubism.ui-main-toolbar.mesh-tool-mode.tool-panel",
                "cubism.ui-main-toolbar.mesh-tool-panel.panel",
                "cubism.ui-main-toolbar.mesh-tool-panel.arrow-button",
                "cubism.ui-main-toolbar.abstract-button.set-selected",
                "cubism.ui-main-toolbar.abstract-button.get-jabstract-button",
                "cubism.ui-main-toolbar.abstract-button.set-icon",
                "cubism.ui-main-toolbar.abstract-button.set-pressed-icon",
                "cubism.ui-main-toolbar.abstract-button.set-selected-icon",
                "cubism.ui-main-toolbar.abstract-button.set-disabled-icon",
                "cubism.ui-main-toolbar.abstract-button.set-disabled-selected-icon",
                "cubism.ui-main-toolbar.slider.create",
                "cubism.ui-main-toolbar.slider.set-min",
                "cubism.ui-main-toolbar.slider.set-max",
                "cubism.ui-main-toolbar.slider.set-value",
                "cubism.ui-main-toolbar.slider.min",
                "cubism.ui-main-toolbar.slider.max",
                "cubism.ui-main-toolbar.slider.value",
                "cubism.ui-main-toolbar.slider.set-on-changed");
    }

    private static SliceExpectation coreExpectation(
            final String profile,
            final String exactVersion,
            final long artifactSize,
            final String artifactSha256,
            final String recordSha256,
            final int selectorCount,
            final String expectedCubismVersion) {
        final Set<String> aliases =
                CorePublicApiSelectorContract.requiredAliasesFor(profile).orElseThrow();
        final Set<String> methods = CorePublicApiSelectorContract.structuralMethodAliasesFor(profile)
                .orElseThrow();
        final Set<String> ownedMocMethods =
                CorePublicApiSelectorContract.ownedMocMethodAliasesFor(profile).orElseThrow();
        final Set<String> versionMethods = CorePublicApiSelectorContract.VERSION_PROBE_ALIASES.stream()
                .filter(alias -> !alias.endsWith(".class"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final Set<String> allMethods = new HashSet<>(methods);
        allMethods.addAll(ownedMocMethods);
        allMethods.addAll(versionMethods);
        return new SliceExpectation(
                CorePublicApiTrustRoots.verificationId(profile),
                CorePublicApiSelectorContract.ADAPTER_SLICE_ID,
                exactVersion,
                "cubism-" + profile,
                CorePublicApiSelectorContract.CAPABILITY_IDS,
                "Live2DCubismCore.jar",
                artifactSize,
                artifactSha256,
                recordSha256,
                selectorCount,
                aliases,
                aliases,
                Set.copyOf(allMethods),
                difference(aliases, allMethods),
                "cubism-" + profile + "-core-model-read",
                Path.of("compatibility/cubism/mapping-packs/draft/cubism-" + profile + "-core-model-read.json"),
                Path.of("compatibility/cubism/profiles/draft/cubism-" + profile + ".json"),
                expectedCubismVersion,
                SliceKind.CORE);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final StaticVerificationRecordValidator validator = new StaticVerificationRecordValidator();
    private final MappingPackValidator mappingPackValidator = new MappingPackValidator();

    @Test
    void everyStaticVerificationRecordMatchesItsRegisteredRepositoryExpectation() throws Exception {
        assertTrue(Files.isDirectory(RECORDS), "static verification record directory must exist");
        final Map<String, Path> discovered = discoverRecords();
        assertFalse(discovered.isEmpty(), "at least one static verification record must exist");
        assertEquals(
                EXPECTATIONS.keySet(),
                discovered.keySet(),
                "registered expectations and tracked records must match bidirectionally");

        final Set<String> verificationIds = new HashSet<>();
        final Set<String> sliceVersions = new HashSet<>();
        final var slices = new ArrayList<org.junit.jupiter.api.function.Executable>();
        for (Map.Entry<String, Path> discoveredRecord : discovered.entrySet()) {
            final JsonNode record = mapper.readTree(discoveredRecord.getValue().toFile());
            assertTrue(
                    verificationIds.add(record.get("verificationId").asText()),
                    "duplicate verificationId: " + record.get("verificationId").asText());
            final String sliceVersion = record.get("adapterSliceId").asText() + "@"
                    + record.get("cubismVersion").asText();
            assertTrue(sliceVersions.add(sliceVersion), "duplicate (adapterSliceId,cubismVersion): " + sliceVersion);
            slices.add(() ->
                    verifySlice(discoveredRecord.getValue(), record, EXPECTATIONS.get(discoveredRecord.getKey())));
        }
        assertAll("static verification records", slices);
    }

    private Map<String, Path> discoverRecords() throws Exception {
        final Map<String, Path> discovered = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(RECORDS)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                final String relativePath = repositoryPath(path);
                assertTrue(
                        discovered.put(relativePath, path) == null,
                        "duplicate static verification record path: " + relativePath);
            }
        }
        return Map.copyOf(discovered);
    }

    private void verifySlice(final Path recordPath, final JsonNode record, final SliceExpectation expectation)
            throws Exception {
        final String repositoryPath = repositoryPath(recordPath);
        assertTrue(
                validator.validate(record, repositoryPath).isEmpty(),
                recordPath.getFileName() + " validation failed: " + validator.validate(record, repositoryPath));
        assertEquals(repositoryPath, record.get("evidencePath").asText(), "record must self-reference its path");
        assertEquals("VERIFIED_STATIC", record.get("status").asText());
        assertFalse(
                record.get("cubismVersion").asText().contains("[")
                        || record.get("cubismVersion").asText().contains(","),
                "record must use an exact Cubism version");

        assertEquals(expectation.verificationId(), record.get("verificationId").asText());
        assertEquals(expectation.adapterSliceId(), record.get("adapterSliceId").asText());
        assertEquals(expectation.cubismVersion(), record.get("cubismVersion").asText());
        assertEquals(expectation.profileId(), record.get("profileId").asText());
        assertEquals(expectation.capabilityIds(), asStringSet(record.get("capabilityIds")));
        assertEquals(
                expectation.artifactName(), record.get("artifact").get("name").asText());
        assertEquals(
                expectation.artifactSize(), record.get("artifact").get("size").asLong());
        assertEquals(
                expectation.artifactSha256(),
                record.get("artifact").get("sha256").asText());
        assertEquals(
                expectation.recordSha256(),
                HostArtifactDigest.from(recordPath).sha256(),
                repositoryPath + ": verified record digest drifted");
        assertEquals(
                expectation.selectorCount(),
                record.get("selectors").size(),
                repositoryPath + ": verified selector count drifted");

        final JsonNode pack =
                mapper.readTree(PROJECT_ROOT.resolve(expectation.packPath()).toFile());
        assertEquals("DRAFT", pack.get("status").asText(), "mapping pack readiness must remain DRAFT");
        if (EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID.equals(expectation.adapterSliceId())) {
            assertTrue(
                    mappingPackValidator
                            .validate(pack, expectation.packPath().toString())
                            .isEmpty(),
                    expectation.packPath() + " validation failed: "
                            + mappingPackValidator.validate(
                                    pack, expectation.packPath().toString()));
        }
        final String packVersion = pack.get("cubismVersion").asText();
        if (expectation.kind() == SliceKind.CORE || expectation.kind() == SliceKind.EDITOR_MODEL) {
            assertTrue(
                    expectation.profileId().equals("cubism-" + packVersion), "Core mapping pack profile label drift");
        } else {
            assertEquals(expectation.cubismVersion(), packVersion, "mapping pack version drift");
        }
        final Map<String, JsonNode> packEntries =
                uniqueNodesBy(pack.get("entries"), "semanticName", expectation.packId() + " pack");
        final Map<String, JsonNode> selectors =
                uniqueNodesBy(record.get("selectors"), "mappingId", expectation.adapterSliceId() + " record");
        assertEquals(expectation.selectorCount(), packEntries.size(), "DRAFT dependency count drifted");
        assertEquals(
                packEntries.keySet(),
                selectors.keySet(),
                "record selectors and mapping entries must match bidirectionally");

        final Set<String> aliases = new HashSet<>();
        final Set<String> methodAliases = new HashSet<>();
        final Set<String> attestationClassAliases = new HashSet<>();
        for (Map.Entry<String, JsonNode> selectorEntry : selectors.entrySet()) {
            verifySelector(
                    selectorEntry.getKey(),
                    selectorEntry.getValue(),
                    packEntries.get(selectorEntry.getKey()),
                    expectation,
                    aliases,
                    methodAliases,
                    attestationClassAliases);
        }
        assertEquals(expectation.manifestAliases(), aliases, "manifest aliases drifted from verified record");
        assertEquals(
                expectation.implementationAliases(),
                aliases,
                "HostOperations required aliases drifted from verified record");
        assertEquals(
                expectation.methodAliases(),
                methodAliases,
                "implementation method aliases drifted from verified record");
        assertEquals(
                expectation.attestationClassAliases(),
                attestationClassAliases,
                "implementation attestation class aliases drifted from verified record");

        verifyProfile(expectation);
    }

    private static void verifySelector(
            final String mappingId,
            final JsonNode selector,
            final JsonNode packEntry,
            final SliceExpectation expectation,
            final Set<String> aliases,
            final Set<String> methodAliases,
            final Set<String> attestationClassAliases) {
        assertTrue(aliases.add(selector.get("alias").asText()), mappingId + " duplicate alias");
        assertEquals("VERIFIED_STATIC", selector.get("status").asText(), mappingId + " selector status drift");
        assertEquals(packEntry.get("name").asText(), selector.get("alias").asText(), mappingId + " alias drift");
        assertEquals(packEntry.get("kind").asText(), selector.get("kind").asText(), mappingId + " kind drift");
        assertEquals(
                packEntry.get("x.verification").get("ownerInternalName").asText(),
                selector.get("ownerInternalName").asText(),
                mappingId + " owner drift");
        assertEquals(
                packEntry.get("x.verification").get("requiredAccessFlags").asInt(),
                selector.get("requiredAccessFlags").asInt(),
                mappingId + " required access drift");
        assertEquals(
                packEntry.get("x.verification").get("forbiddenAccessFlags").asInt(),
                selector.get("forbiddenAccessFlags").asInt(),
                mappingId + " forbidden access drift");
        assertEquals(expectation.profileId(), packEntry.get("profile").asText(), mappingId + " profile drift");
        assertEquals("DRAFT", packEntry.get("status").asText(), mappingId + " mapping readiness drift");

        if ("class".equals(selector.get("kind").asText())) {
            attestationClassAliases.add(selector.get("alias").asText());
            assertEquals(
                    packEntry.get("runtime").asText(),
                    selector.get("ownerInternalName").asText(),
                    mappingId + " class owner drift");
            assertTrue(selector.get("memberName").isNull(), mappingId + " class member must be null");
            assertTrue(selector.get("descriptor").isNull(), mappingId + " class descriptor must be null");
        } else if ("inherits".equals(selector.get("kind").asText())) {
            assertEquals(
                    packEntry.get("runtime").asText(),
                    selector.get("memberName").asText(),
                    mappingId + " inherited type drift");
            assertTrue(selector.get("descriptor").isNull(), mappingId + " inheritance descriptor must be null");
            assertTrue(packEntry.get("descriptor").isNull(), mappingId + " DRAFT inheritance descriptor must be null");
        } else {
            methodAliases.add(selector.get("alias").asText());
            assertEquals(
                    packEntry.get("runtime").asText(),
                    selector.get("memberName").asText(),
                    mappingId + " member drift");
            assertEquals(
                    packEntry.get("descriptor").asText(),
                    selector.get("descriptor").asText(),
                    mappingId + " descriptor drift");
        }
    }

    private void verifyProfile(final SliceExpectation expectation) throws Exception {
        final JsonNode profile =
                mapper.readTree(PROJECT_ROOT.resolve(expectation.profilePath()).toFile());
        assertEquals("DRAFT", profile.get("status").asText(), "profile readiness must remain DRAFT");
        assertEquals(expectation.profileId(), profile.get("profileId").asText());
        assertTrue(
                asStringSet(profile.get("mappingPacks")).contains(expectation.packId()),
                "profile must reference " + expectation.packId());
        assertEquals(
                expectation.expectedCubismVersion(),
                profile.get("cubismVersion").asText(),
                "profile version range drifted");
        if (expectation.kind() == SliceKind.CLIP_MASK) {
            assertFalse(
                    asStringSet(profile.get("mappingPacks")).contains("cubism-5.3.02-m15-project-workspace-clipmask"),
                    "clip-mask evidence must not be coupled to a project/workspace pack");
        }
    }

    private static Map<String, JsonNode> uniqueNodesBy(
            final JsonNode array, final String identityField, final String source) {
        final Map<String, JsonNode> nodes = new LinkedHashMap<>();
        for (JsonNode node : array) {
            final String identity = node.get(identityField).asText();
            assertTrue(nodes.put(identity, node) == null, source + " has duplicate " + identityField + ": " + identity);
        }
        return Map.copyOf(nodes);
    }

    private static String repositoryPath(final Path path) {
        return PROJECT_ROOT.relativize(path).toString().replace('\\', '/');
    }

    private static Set<String> intersection(final Set<String> left, final Set<String> right) {
        final Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return Set.copyOf(intersection);
    }

    /**
     * Every project-workspace record alias must be claimed by a declared
     * consumer: the project/workspace slice itself plus the recent-preview
     * fallback contract ({@link RecentPreviewVerificationManifest#PROJECT_REQUIRED_ALIASES}),
     * which consumes the view-context members and pins their class attestation
     * anchor.
     */
    private static Set<String> projectWorkspaceImplementationAliases() {
        return union(
                VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES,
                RecentPreviewVerificationManifest.PROJECT_REQUIRED_ALIASES);
    }

    private static Set<String> projectWorkspaceAttestationClassAliases() {
        return union(
                difference(
                        VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES,
                        VerifiedProjectWorkspaceHostOperations.methodAliasesUsed()),
                RecentPreviewVerificationManifest.PROJECT_ATTESTATION_CLASS_ALIASES);
    }

    private static Set<String> projectWorkspaceMemberAliases() {
        return difference(projectWorkspaceImplementationAliases(), projectWorkspaceAttestationClassAliases());
    }

    private static Set<String> union(final Set<String> first, final Set<String> second) {
        final Set<String> union = new HashSet<>(first);
        union.addAll(second);
        return Set.copyOf(union);
    }

    private static Set<String> difference(final Set<String> all, final Set<String> excluded) {
        final Set<String> difference = new HashSet<>(all);
        difference.removeAll(excluded);
        return Set.copyOf(difference);
    }

    private static Set<String> asStringSet(final JsonNode array) {
        final Set<String> values = new HashSet<>();
        array.forEach(value -> assertTrue(values.add(value.asText()), "duplicate array value: " + value.asText()));
        return Set.copyOf(values);
    }

    private enum SliceKind {
        PROJECT_WORKSPACE,
        CLIP_MASK,
        CORE,
        EDITOR_MODEL,
        EDITOR_UI,
        PERFORMANCE,
        PROTECTED_EXPORT
    }

    private record SliceExpectation(
            String verificationId,
            String adapterSliceId,
            String cubismVersion,
            String profileId,
            Set<String> capabilityIds,
            String artifactName,
            long artifactSize,
            String artifactSha256,
            String recordSha256,
            int selectorCount,
            Set<String> manifestAliases,
            Set<String> implementationAliases,
            Set<String> methodAliases,
            Set<String> attestationClassAliases,
            String packId,
            Path packPath,
            Path profilePath,
            String expectedCubismVersion,
            SliceKind kind) {}
}
