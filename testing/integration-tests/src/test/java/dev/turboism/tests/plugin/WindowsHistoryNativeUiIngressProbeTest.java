package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the operator-driven probe's verdicts.
 *
 * <p>Each operator step is judged only on what the runtime actually published for it, so these
 * cases fix what counts as the hook firing, what counts as navigation, and what must be reported
 * instead of silently passing.</p>
 */
class WindowsHistoryNativeUiIngressProbeTest {

    private static final WindowsHistoryNativeUiIngressProbe.Step ACTION =
        new WindowsHistoryNativeUiIngressProbe.Step("parts-tree-drag", "ACTION", "drag");
    private static final WindowsHistoryNativeUiIngressProbe.Step UNDO =
        new WindowsHistoryNativeUiIngressProbe.Step("native-undo", "UNDO", "ctrl-z");
    private static final WindowsHistoryNativeUiIngressProbe.Step REDO =
        new WindowsHistoryNativeUiIngressProbe.Step("native-redo", "REDO", "ctrl-y");

    @Test
    void anOperatorActionMustBeAnnouncedBeforeItIsObserved() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 1, "HOST_UI"),
                    event("on", 2, "HOST_UI"),
                    event("after", 2, "HOST_UI")
                )
            );

        assertTrue(verdict.ok(), verdict.detail());
        assertEquals("action-hook-and-host-ui-pair-observed", verdict.code());
    }

    @Test
    void anOperatorActionWithoutABeforeEventIsAFailure() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(event("on", 2, "HOST_UI"), event("after", 3, "HOST_UI"))
            );

        assertFalse(verdict.ok());
        assertEquals("no-before-event", verdict.code());
    }

    @Test
    void anOperatorActionThatWasNeverConfirmedIsAFailure() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(event("before", 1, "HOST_UI"))
            );

        assertFalse(verdict.ok());
        assertEquals("no-confirmed-event", verdict.code());
    }

    @Test
    void anEditEntryEnteredDuringNavigationIsAFailure() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO,
                List.of(event("before", 7, "HOST_UI"), event("on", 8, "UNDO"))
            );

        assertFalse(verdict.ok(), "a native Undo does not enter the edit entry");
        assertEquals("navigation-was-announced-as-an-edit", verdict.code());
    }

    @Test
    void nativeNavigationMustBeAttributedAndMayNotLookLikeAnEdit() {
        final WindowsHistoryNativeUiIngressProbe.Verdict undo =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO, List.of(navigationEvent("on", 4, "UNDO"), navigationEvent("after", 4, "UNDO"))
            );
        assertTrue(undo.ok(), undo.detail());
        assertEquals("navigation-pair-confirmed-without-an-edit", undo.code());

        final WindowsHistoryNativeUiIngressProbe.Verdict redo =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                REDO, List.of(navigationEvent("on", 6, "REDO"), navigationEvent("after", 6, "REDO"))
            );
        assertTrue(redo.ok(), redo.detail());

        final WindowsHistoryNativeUiIngressProbe.Verdict unattributed =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO, List.of(event("on", 4, "HOST_UI"), event("after", 4, "HOST_UI"))
            );
        assertFalse(unattributed.ok(), "an un-attributed navigation is not evidence of Undo");
        assertEquals("navigation-not-attributed", unattributed.code());

        final WindowsHistoryNativeUiIngressProbe.Verdict silent =
            WindowsHistoryNativeUiIngressProbe.checkStep(UNDO, List.of());
        assertFalse(silent.ok());
        assertEquals("navigation-not-attributed", silent.code());
    }

    @Test
    void r82ActionFixtureAcceptsIndependentBeforeAndMultipleHostUiPairs() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 1, "HOST_UI", "选择对象"),
                    event("on", 2, "HOST_UI", "选择对象"),
                    event("after", 2, "HOST_UI", "选择对象"),
                    event("before", 3, "HOST_UI", "物体的移动"),
                    event("on", 4, "HOST_UI", "物体的移动"),
                    event("after", 4, "HOST_UI", "物体的移动")
                )
            );

        assertTrue(verdict.ok(), verdict.detail());
        assertEquals("action-hook-and-host-ui-pair-observed", verdict.code());
        assertEquals("before=2,confirmed=4,events=6", verdict.detail());
    }

    @Test
    void r82NavigationFixturesRequireTheExactOperationAndOriginPair() {
        final WindowsHistoryNativeUiIngressProbe.Verdict undo =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO,
                List.of(navigationEvent("on", 21, "UNDO"), navigationEvent("after", 21, "UNDO"))
            );
        final WindowsHistoryNativeUiIngressProbe.Verdict redo =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                REDO,
                List.of(navigationEvent("on", 22, "REDO"), navigationEvent("after", 22, "REDO"))
            );

        assertTrue(undo.ok(), undo.detail());
        assertTrue(redo.ok(), redo.detail());
    }

    @Test
    void r82ActionFixtureKeepsUnpairedGenericBeforeAsHookEvidence() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 16, "HOST_UI", "选择对象"),
                    event("on", 17, "HOST_UI", "选择对象"),
                    event("after", 17, "HOST_UI", "选择对象"),
                    event("before", 18, "HOST_UI", "正片叠底色 の編集"),
                    event("before", 19, "HOST_UI", "全选"),
                    event("on", 20, "HOST_UI", "全选"),
                    event("after", 20, "HOST_UI", "全选")
                )
            );

        assertTrue(verdict.ok(), verdict.detail());
    }

    @Test
    void anActionWithOnlyOneConfirmationPhaseFailsClosed() {
        final WindowsHistoryNativeUiIngressProbe.Verdict onlyOn =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(event("before", 1, "HOST_UI"), event("on", 2, "HOST_UI"))
            );
        final WindowsHistoryNativeUiIngressProbe.Verdict onlyAfter =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(event("before", 1, "HOST_UI"), event("after", 2, "HOST_UI"))
            );

        assertFalse(onlyOn.ok());
        assertEquals("orphan-on-event", onlyOn.code());
        assertFalse(onlyAfter.ok());
        assertEquals("orphan-after-event", onlyAfter.code());
    }

    @Test
    void confirmationAfterMustFollowOnAndReuseItsSequence() {
        final WindowsHistoryNativeUiIngressProbe.Verdict reversed =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 1, "HOST_UI"),
                    event("after", 2, "HOST_UI"),
                    event("on", 2, "HOST_UI")
                )
            );
        final WindowsHistoryNativeUiIngressProbe.Verdict wrongSequence =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 1, "HOST_UI"),
                    event("on", 2, "HOST_UI"),
                    event("after", 3, "HOST_UI")
                )
            );

        assertFalse(reversed.ok());
        assertEquals("orphan-after-event", reversed.code());
        assertFalse(wrongSequence.ok());
        assertEquals("confirmation-sequence-mismatch", wrongSequence.code());
    }

    @Test
    void confirmationRejectsDuplicatePhasesAndMismatchedFields() {
        final WindowsHistoryNativeUiIngressProbe.Verdict duplicate =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 1, "HOST_UI"),
                    event("on", 2, "HOST_UI"),
                    event("on", 2, "HOST_UI"),
                    event("after", 2, "HOST_UI")
                )
            );
        final WindowsHistoryNativeUiIngressProbe.Verdict mismatched =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 1, "HOST_UI"),
                    eventWithFields("on", 2, "operation-a", "HOST_UI", "subject-a", "label-a"),
                    eventWithFields("after", 2, "operation-b", "HOST_UI", "subject-a", "label-b")
                )
            );

        assertFalse(duplicate.ok());
        assertEquals("duplicate-confirmation-phase", duplicate.code());
        assertFalse(mismatched.ok());
        assertEquals("confirmation-fields-mismatch", mismatched.code());
    }

    @Test
    void actionRequiresAHostUiConfirmationAndDoesNotUseLabelsAsIdentity() {
        final WindowsHistoryNativeUiIngressProbe.Verdict wrongOrigin =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 1, "HOST_UI", "selection label"),
                    eventWithFields("on", 2, "operation", "OTHER", "", "same visible label"),
                    eventWithFields("after", 2, "operation", "OTHER", "", "same visible label")
                )
            );
        final WindowsHistoryNativeUiIngressProbe.Verdict blankOperation =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 1, "HOST_UI"),
                    eventWithFields("on", 2, "", "HOST_UI", "", "selection label"),
                    eventWithFields("after", 2, "", "HOST_UI", "", "selection label")
                )
            );

        assertFalse(wrongOrigin.ok());
        assertEquals("confirmation-not-host-ui", wrongOrigin.code());
        assertFalse(blankOperation.ok());
        assertEquals("confirmation-fields-missing", blankOperation.code());
    }

    @Test
    void navigationRejectsWrongOperationOriginAndMultiplePairs() {
        final WindowsHistoryNativeUiIngressProbe.Verdict wrongOperation =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO,
                List.of(
                    navigationEvent("on", 21, "UNDO", "EXECUTE_EDITOR_COMMAND"),
                    navigationEvent("after", 21, "UNDO", "EXECUTE_EDITOR_COMMAND")
                )
            );
        final WindowsHistoryNativeUiIngressProbe.Verdict wrongOrigin =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                REDO,
                List.of(
                    navigationEvent("on", 22, "HOST_UI"),
                    navigationEvent("after", 22, "HOST_UI")
                )
            );
        final WindowsHistoryNativeUiIngressProbe.Verdict doubleUndo =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                UNDO,
                List.of(
                    navigationEvent("on", 21, "UNDO"),
                    navigationEvent("after", 21, "UNDO"),
                    navigationEvent("on", 23, "UNDO"),
                    navigationEvent("after", 23, "UNDO")
                )
            );

        assertFalse(wrongOperation.ok());
        assertEquals("navigation-not-attributed", wrongOperation.code());
        assertFalse(wrongOrigin.ok());
        assertEquals("navigation-not-attributed", wrongOrigin.code());
        assertFalse(doubleUndo.ok());
        assertEquals("navigation-requires-one-confirmation-pair", doubleUndo.code());
    }

    @Test
    void confirmationPairOrderDoesNotDependOnNumericSequenceOrObservedTime() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                ACTION,
                List.of(
                    event("before", 30, "HOST_UI"),
                    eventWithFields("on", 20, "operation-a", "HOST_UI", "", "later label"),
                    eventWithFields("after", 20, "operation-a", "HOST_UI", "", "earlier label"),
                    eventWithFields("on", 10, "operation-b", "HOST_UI", "", "next label"),
                    eventWithFields("after", 10, "operation-b", "HOST_UI", "", "next label")
                )
            );

        assertTrue(verdict.ok(), verdict.detail());
    }

    @Test
    void unknownStepKindFailsClosed() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkStep(
                new WindowsHistoryNativeUiIngressProbe.Step("future", "FUTURE", "future"),
                List.of(event("before", 1, "HOST_UI"), event("on", 2, "HOST_UI"), event("after", 2, "HOST_UI"))
            );

        assertFalse(verdict.ok());
        assertEquals("unknown-step-kind", verdict.code());
    }

    @Test
    void partsSemanticVerifierProvesOneCompleteTargetToTargetMembership() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            partVerdict(membershipDetail(
                targetEndpoint("PART", "part-a", "A"),
                targetEndpoint("PART", "part-b", "B"),
                HistoryAction.DetailLevel.FULL
            ));

        assertTrue(verdict.ok(), verdict.detail());
        assertEquals("proven-membership-change", verdict.code());
    }

    @Test
    void partsSemanticVerifierAcceptsAConfirmedRootEndpoint() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            partVerdict(membershipDetail(
                rootEndpoint(),
                targetEndpoint("PART", "part-b", "B"),
                HistoryAction.DetailLevel.FULL
            ));

        assertTrue(verdict.ok(), verdict.detail());
    }

    @Test
    void partsSemanticVerifierAcceptsDetachingToRoot() {
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            partVerdict(membershipDetail(
                targetEndpoint("PART", "part-a", "A"),
                rootEndpoint(),
                HistoryAction.DetailLevel.FULL
            ));

        assertTrue(verdict.ok(), verdict.detail());
    }

    @Test
    void partsSemanticVerifierRecursesThroughCompleteGroups() {
        final HistoryEntryDetail child = membershipDetail(
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );
        final HistoryEntryDetail grouped = new HistoryEntryDetail(
            "group",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(new HistoryGroup(Optional.of("group-1"), 1, List.of(child), false)),
            Optional.empty()
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict = partVerdict(grouped);

        assertTrue(verdict.ok(), verdict.detail());
    }

    @Test
    void selectionOnlyAndMissingRelationCannotProveMembership() {
        assertEquals(
            "no-proven-membership-change",
            partVerdict(HistoryEntryDetail.labelOnly("selection")).code()
        );
        assertEquals(
            "no-proven-membership-change",
            partVerdict(missingRelationDetail()).code()
        );
    }

    @Test
    void aSelectionOnlyAuxiliaryEntryDoesNotSupplyOrContaminateMembershipProof() {
        final HistoryEntryDetail complete = membershipDetail(
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict = partVerdict(
            List.of(HistoryEntryDetail.labelOnly("selection"), complete)
        );

        assertTrue(verdict.ok(), verdict.detail());
        assertEquals("proven-membership-change", verdict.code());
    }

    @Test
    void unknownEndpointOnEitherSideIsPartialAndNeverTreatedAsRoot() {
        final WindowsHistoryNativeUiIngressProbe.Verdict unknownBefore = partVerdict(
            membershipDetail(
                unknownEndpoint(),
                targetEndpoint("PART", "part-b", "B"),
                HistoryAction.DetailLevel.PARTIAL
            )
        );
        final WindowsHistoryNativeUiIngressProbe.Verdict unknownAfter = partVerdict(
            membershipDetail(
                targetEndpoint("PART", "part-a", "A"),
                unknownEndpoint(),
                HistoryAction.DetailLevel.PARTIAL
            )
        );

        assertFalse(unknownBefore.ok());
        assertEquals("partial-relation", unknownBefore.code());
        assertFalse(unknownAfter.ok());
        assertEquals("partial-relation", unknownAfter.code());
    }

    @Test
    void twoPartialEntriesAreNotCoalescedIntoACompleteMove() {
        final HistoryEntryDetail remove = membershipDetail(
            targetEndpoint("PART", "part-a", "A"),
            unknownEndpoint(),
            HistoryAction.DetailLevel.PARTIAL
        );
        final HistoryEntryDetail add = membershipDetail(
            unknownEndpoint(),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.PARTIAL
        );
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            partVerdict(List.of(remove, add));

        assertFalse(verdict.ok());
        assertEquals("partial-relation", verdict.code());
    }

    @Test
    void sameEndpointAndNonMembershipRelationsAreRejected() {
        final HistoryTarget same = new HistoryTarget(
            "PART", Optional.of("part-a"), Optional.of("A")
        );
        final HistoryRelationChange unchanged = new HistoryRelationChange(
            HistoryRelationChange.Kind.PART_MEMBERSHIP,
            targetEndpoint(same),
            targetEndpoint(same)
        );
        final WindowsHistoryNativeUiIngressProbe.Verdict sameEndpoint =
            partVerdict(relationDetail(unchanged, HistoryAction.DetailLevel.PARTIAL));
        final HistoryRelationChange deformer = new HistoryRelationChange(
            HistoryRelationChange.Kind.DEFORMER_PARENT,
            targetEndpoint("WARP_DEFORMER", "warp-a", "A"),
            targetEndpoint("WARP_DEFORMER", "warp-b", "B")
        );
        final WindowsHistoryNativeUiIngressProbe.Verdict wrongKind =
            partVerdict(relationDetail(deformer, HistoryAction.DetailLevel.PARTIAL));

        assertFalse(sameEndpoint.ok());
        assertEquals("no-proven-membership-change", sameEndpoint.code());
        assertFalse(wrongKind.ok());
        assertEquals("no-proven-membership-change", wrongKind.code());
    }

    @Test
    void endpointIdentityIgnoresDisplayNameWhenRejectingAnUnchangedRelation() {
        final HistoryRelationChange unchanged = new HistoryRelationChange(
            HistoryRelationChange.Kind.PART_MEMBERSHIP,
            targetEndpoint("PART", "part-a", "old name"),
            targetEndpoint("PART", "part-a", "renamed name")
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            partVerdict(relationDetail(unchanged, HistoryAction.DetailLevel.PARTIAL));

        assertFalse(verdict.ok());
        assertEquals("no-proven-membership-change", verdict.code());
    }

    @Test
    void conflictingRelationsInOneCandidateFailClosed() {
        final HistoryTarget child = childTarget();
        final HistoryChange first = relationChange(new HistoryRelationChange(
            HistoryRelationChange.Kind.PART_MEMBERSHIP,
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B")
        ));
        final HistoryChange second = relationChange(new HistoryRelationChange(
            HistoryRelationChange.Kind.PART_MEMBERSHIP,
            targetEndpoint("PART", "part-b", "B"),
            rootEndpoint()
        ));
        final HistoryEntryDetail detail = new HistoryEntryDetail(
            "conflict",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(child),
            List.of(first, second),
            Optional.empty(),
            Optional.of("fixture.conflict")
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict = partVerdict(detail);

        assertFalse(verdict.ok());
        assertEquals("ambiguous-candidate", verdict.code());
    }

    @Test
    void completeMembershipMixedWithAPropertyChangeIsAmbiguous() {
        final HistoryRelationChange relation = new HistoryRelationChange(
            HistoryRelationChange.Kind.PART_MEMBERSHIP,
            rootEndpoint(),
            targetEndpoint("PART", "part-b", "B")
        );
        final HistoryChange property = HistoryChange.set(0, "opacity", "1", "0.5");
        final HistoryEntryDetail mixed = new HistoryEntryDetail(
            "mixed",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(childTarget()),
            List.of(relationChange(relation), property),
            Optional.empty(),
            Optional.empty()
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict = partVerdict(mixed);

        assertFalse(verdict.ok());
        assertEquals("ambiguous-candidate", verdict.code());
    }

    @Test
    void nestedGroupSiblingPropertyChangeIsAmbiguous() {
        final HistoryEntryDetail relation = membershipDetail(
            rootEndpoint(),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );
        final HistoryEntryDetail property = new HistoryEntryDetail(
            "opacity",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(childTarget()),
            List.of(HistoryChange.set(0, "opacity", "1", "0.5")),
            Optional.empty(),
            Optional.empty()
        );
        final HistoryEntryDetail grouped = new HistoryEntryDetail(
            "mixed group",
            HistoryAction.DetailLevel.FULL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(new HistoryGroup(
                Optional.of("mixed-group"), 2, List.of(relation, property), false
            )),
            Optional.empty()
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict = partVerdict(grouped);

        assertFalse(verdict.ok());
        assertEquals("ambiguous-candidate", verdict.code());
    }

    @Test
    void multipleCandidateObjectsFailEvenWhenOneRelationIsComplete() {
        final HistoryTarget first = childTarget();
        final HistoryTarget second = new HistoryTarget(
            "ART_MESH", Optional.of("child-2"), Optional.of("Other child")
        );
        final HistoryEntryDetail detail = new HistoryEntryDetail(
            "two objects",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(first, second),
            List.of(relationChange(new HistoryRelationChange(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint("PART", "part-a", "A"),
                targetEndpoint("PART", "part-b", "B")
            ))),
            Optional.empty(),
            Optional.of("fixture.two-targets")
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict = partVerdict(detail);

        assertFalse(verdict.ok());
        assertEquals("ambiguous-candidate", verdict.code());
    }

    @Test
    void aRelationWithoutATargetIndexCannotProveMembership() {
        final HistoryChange missingTargetIndex = new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()),
            Optional.empty()
        );
        final HistoryEntryDetail detail = new HistoryEntryDetail(
            "missing target index",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(childTarget()),
            List.of(missingTargetIndex),
            Optional.empty(),
            Optional.of("fixture.target-index-missing")
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict = partVerdict(detail);

        assertFalse(verdict.ok());
        assertEquals("no-proven-membership-change", verdict.code());
    }

    @Test
    void outOfRangeRelationTargetIndexIsRejectedByTheTypedSdkDetail() {
        final HistoryRelationChange relation = new HistoryRelationChange(
            HistoryRelationChange.Kind.PART_MEMBERSHIP,
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B")
        );
        final HistoryChange invalid = new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(1),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()),
            Optional.of(relation)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoryEntryDetail(
                "invalid target index",
                HistoryAction.DetailLevel.PARTIAL,
                HistoryOrigin.hostUnattributed(),
                List.of(childTarget()),
                List.of(invalid),
                Optional.empty(),
                Optional.of("fixture.target-index-invalid")
            )
        );
    }

    @Test
    void truncatedGroupCannotClaimCompleteMembership() {
        final HistoryEntryDetail child = membershipDetail(
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );
        final HistoryEntryDetail truncated = new HistoryEntryDetail(
            "truncated",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(),
            List.of(),
            Optional.of(new HistoryGroup(Optional.of("group-1"), 2, List.of(child), true)),
            Optional.of("fixture.truncated")
        );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict = partVerdict(truncated);

        assertFalse(verdict.ok());
        assertEquals("partial-relation", verdict.code());
    }

    @Test
    void oldEntriesAndRedoTailAreNotNewAppliedCandidates() {
        final HistoryEntryDetail complete = membershipDetail(
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );
        final WindowsHistoryManagerValidationProbe.SdkEntry old =
            sdkEntry(0, "old", HistoryEntryDetail.labelOnly("old"));
        final WindowsHistoryManagerValidationProbe.SdkEntry redo =
            sdkEntry(1, "redo-tail", complete);
        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                sdkHistory(List.of(old, redo), 1),
                sdkHistory(List.of(old, redo), 1)
            );

        assertFalse(verdict.ok());
        assertEquals("no-new-entry", verdict.code());
    }

    @Test
    void candidateMustHaveAStableIdAndBeInsideTheAppliedAfterRange() {
        final HistoryEntryDetail complete = membershipDetail(
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );
        final WindowsHistoryManagerValidationProbe.SdkEntry old =
            sdkEntry(0, "old", HistoryEntryDetail.labelOnly("old"));
        final WindowsHistoryManagerValidationProbe.SdkEntry noId =
            sdkEntry(1, "", complete);
        final WindowsHistoryManagerValidationProbe.SdkEntry unapplied =
            sdkEntry(1, "unapplied", complete);

        assertEquals(
            "history-unavailable",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                sdkHistory(List.of(old), 1),
                sdkHistory(List.of(old, noId), 2)
            ).code()
        );
        assertEquals(
            "no-new-entry",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                sdkHistory(List.of(old), 1),
                sdkHistory(List.of(old, unapplied), 1)
            ).code()
        );
    }

    @Test
    void blankOrDuplicateEntryIdsInEitherSnapshotFailClosed() {
        final HistoryEntryDetail complete = membershipDetail(
            rootEndpoint(),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );
        final WindowsHistoryManagerValidationProbe.SdkEntry old =
            sdkEntry(0, "old", HistoryEntryDetail.labelOnly("old"));
        final WindowsHistoryManagerValidationProbe.SdkEntry blank =
            sdkEntry(1, "", HistoryEntryDetail.labelOnly("selection"));
        final WindowsHistoryManagerValidationProbe.SdkEntry fresh =
            sdkEntry(1, "fresh", complete);

        assertEquals(
            "history-unavailable",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                sdkHistory(List.of(sdkEntry(0, "", complete)), 1),
                sdkHistory(List.of(sdkEntry(0, "known-now", complete)), 1)
            ).code()
        );
        assertEquals(
            "history-unavailable",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                sdkHistory(List.of(old), 1),
                sdkHistory(List.of(old, fresh, blank), 3)
            ).code()
        );
        assertEquals(
            "history-unavailable",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                sdkHistory(List.of(old), 1),
                sdkHistory(List.of(old, fresh, sdkEntry(2, "fresh", complete)), 3)
            ).code()
        );
        assertEquals(
            "history-unavailable",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                sdkHistory(List.of(old, sdkEntry(1, "old", HistoryEntryDetail.labelOnly("old"))), 2),
                sdkHistory(List.of(old, sdkEntry(1, "old", HistoryEntryDetail.labelOnly("old"))), 2)
            ).code()
        );
    }

    @Test
    void unavailableBindingChangeGenerationAndTruncationFailClosed() {
        final HistoryEntryDetail complete = membershipDetail(
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );
        final WindowsHistoryManagerValidationProbe.SdkEntry old =
            sdkEntry(0, "old", HistoryEntryDetail.labelOnly("old"));
        final WindowsHistoryManagerValidationProbe.SdkEntry fresh =
            sdkEntry(1, "fresh", complete);
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot before =
            sdkHistory(List.of(old), 1);
        final WindowsHistoryManagerValidationProbe.SdkHistorySnapshot after =
            sdkHistory(List.of(old, fresh), 2);

        assertEquals(
            "history-unavailable",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot(
                    "UNAVAILABLE", 0, 0, 0, false, false, "", "", 0, List.of()
                ),
                after
            ).code()
        );
        assertEquals(
            "binding-changed",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                before,
                new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot(
                    "AVAILABLE", 7, 2, 2, true, false,
                    "document-2", "manager-1", 2, List.of(old, fresh)
                )
            ).code()
        );
        assertEquals(
            "binding-changed",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                before,
                new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot(
                    "AVAILABLE", 8, 2, 2, true, false,
                    "document-1", "manager-1", 2, List.of(old, fresh)
                )
            ).code()
        );
        assertEquals(
            "history-truncated",
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                before,
                new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot(
                    "AVAILABLE", 7, 2, 2, true, false,
                    "document-1", "manager-1", 3, List.of(old, fresh)
                )
            ).code()
        );
    }

    @Test
    void legacyJsonOnlySdkEntryCannotBeParsedIntoAFalseSemanticProof() {
        final HistoryEntryDetail complete = membershipDetail(
            targetEndpoint("PART", "part-a", "A"),
            targetEndpoint("PART", "part-b", "B"),
            HistoryAction.DetailLevel.FULL
        );
        final WindowsHistoryManagerValidationProbe.SdkEntry old =
            sdkEntry(0, "old", HistoryEntryDetail.labelOnly("old"));
        final WindowsHistoryManagerValidationProbe.SdkEntry jsonOnly =
            new WindowsHistoryManagerValidationProbe.SdkEntry(
                1,
                "new",
                "new",
                WindowsHistoryManagerValidationProbe.sdkDetailJson(complete, 0)
            );

        final WindowsHistoryNativeUiIngressProbe.Verdict verdict =
            WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
                sdkHistory(List.of(old), 1),
                sdkHistory(List.of(old, jsonOnly), 2)
            );

        assertFalse(verdict.ok());
        assertEquals("no-proven-membership-change", verdict.code());
    }

    @Test
    void theRecordedEventCarriesThePhaseTheNameAndTheOrigin() {
        final String json = event("before", 12, "HOST_UI").json("canvas-move");

        assertTrue(json.contains("\"step\":\"canvas-move\""), json);
        assertTrue(json.contains("\"phase\":\"before\""), json);
        assertTrue(json.contains("\"sequence\":12"), json);
        assertTrue(json.contains("\"label\":\"Add Part\""), json);
        assertTrue(json.endsWith("}\n"), json);
    }

    @Test
    void theTerminalSummaryLineIsExactlyWhatTheHostRunnerMatches() {
        // result_file_contains compares one whole line, so a summary carrying extra fields would
        // make the runner wait out its entire result timeout after the probe had already finished.
        // The failure list therefore travels on its own line.
        assertEquals(
            "{\"type\":\"summary\",\"status\":\"PASS\"}\n",
            WindowsHistoryNativeUiIngressProbe.summaryLine(true)
        );
        assertEquals(
            "{\"type\":\"summary\",\"status\":\"FAIL\"}\n",
            WindowsHistoryNativeUiIngressProbe.summaryLine(false)
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.summaryLine(false).contains("failures"),
            "the failure list must not ride on the terminal line"
        );
    }

    @Test
    void aSelectionDoesNotCloseAnActionStep() {
        // The host records a selection as an ordinary undo entry, and this run recorded
        // 选择对象, 拖动选择 and 选择顶点 that way. An ACTION step
        // must be closed only by the significant entries changing, so the click that precedes an
        // action cannot satisfy it.
        final String none = WindowsHistoryNativeUiIngressProbe.significantSequence(List.of());
        final String selectionOnly = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(entry(0, "选择对象", false))
        );
        final String withEdit = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(
                entry(0, "选择对象", false),
                entry(1, "物体的移动", true)
            )
        );

        assertEquals("", none);
        assertEquals("", selectionOnly, "a selection contributes no significant entry");
        assertEquals("1:物体的移动", withEdit);
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, selectionOnly, 1L, none, 0L),
            "a selection advances the position and adds an entry, but is not the operator's action"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, withEdit, 2L, selectionOnly, 1L),
            "the operator's real edit is what closes an ACTION step"
        );
    }

    @Test
    void anUndoOrRedoStepIsClosedByThePositionAndNotByTheEntries() {
        // Undo and Redo move the cursor and add no entry, so an entry-based rule would never fire;
        // an ACTION step must not fire on the position, because a selection moves it too.
        final String significant = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(entry(0, "物体的移动", true))
        );

        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, significant, 0L, significant, 1L),
            "a position change alone must not close an ACTION step"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 0L, significant, 1L),
            "an undo moves the position and changes no entry"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(REDO, significant, 1L, significant, 1L),
            "an unchanged position is not a redo"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, null, 0L, significant, 1L),
            "a sampler that could not read the manager is not a move"
        );
    }

    @Test
    void aNavigationStepIsClosedOnlyByAMoveInItsOwnDirection() {
        // A selection also moves the position — it adds an entry — so accepting any change would
        // let the click before the operator's real action close an Undo step. A Redo must not
        // close an Undo step either, which is what the previous runs misattributed.
        final String significant = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(entry(0, "物体的移动", true))
        );

        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 4L, significant, 5L),
            "an Undo is closed by the position going down"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 6L, significant, 5L),
            "a Redo must not close an Undo step"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 5L, significant, 5L),
            "an unchanged position is not an undo"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(REDO, significant, 6L, significant, 5L),
            "a Redo is closed by the position going up"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(REDO, significant, 4L, significant, 5L),
            "an Undo must not close a Redo step"
        );
        // A selection adds an insignificant entry and moves the position up, so it closes no
        // navigation step at all — including the Undo step it would have closed under the old
        // "any change" rule.
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 6L, significant, 5L),
            "a selection moves the position up and must not close an Undo step"
        );
        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(ACTION, significant, 6L, significant, 5L),
            "a selection changes no significant entry and must not close an ACTION step"
        );
    }

    @Test
    void anUnmappedStepHasNoActor() {
        // The steps without a reviewed actor must report "none" so the step keeps its full
        // operator window; an actor that cannot run reports unresolved/failed instead.
        final WindowsHistoryNativeUiIngressProbe probe = new WindowsHistoryNativeUiIngressProbe();

        for (final String id : List.of(
            "unknown"
        )) {
            assertEquals(
                "none",
                probe.act(new WindowsHistoryNativeUiIngressProbe.Step(id, "ACTION", "x"), ""),
                id
            );
        }
    }

    @Test
    void anActorThatCannotResolveItsControlIsRecordedRatherThanThrown() {
        // A test JVM owns no windows, so the canvas actor must come back unresolved and the
        // accelerator actor must fail through the actor's own Throwable guard — neither may
        // throw or pretend the step is done.
        final WindowsHistoryNativeUiIngressProbe probe = new WindowsHistoryNativeUiIngressProbe();

        final String canvas =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("canvas-move", "ACTION", "x"), "");
        assertTrue(
            canvas.startsWith("unresolved:") || canvas.startsWith("failed:"),
            "an automated actor with no canvas to drive must be recorded, got " + canvas
        );

        final String parameter =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("native-parameter", "ACTION", "x"), "");
        assertTrue(
            parameter.startsWith("unresolved:") || parameter.startsWith("failed:"),
            "an automated actor with no slider to drive must be recorded, got " + parameter
        );

        final String deform =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("canvas-deform", "ACTION", "x"), "");
        assertTrue(
            deform.startsWith("unresolved:") || deform.startsWith("failed:"),
            "the canvas actor with no canvas must be recorded, got " + deform
        );

        final String color =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("native-color", "ACTION", "x"), "");
        assertTrue(
            color.startsWith("unresolved:") || color.startsWith("failed:"),
            "an automated actor with no colour field to drive must be recorded, got " + color
        );

        final String undo =
            probe.act(new WindowsHistoryNativeUiIngressProbe.Step("native-undo", "UNDO", "x"), "");
        assertFalse("none".equals(undo), "a mapped step must attempt its actor");
        assertFalse(undo.isBlank(), "the actor outcome is evidence and must not be empty");
    }

    @Test
    void partActorUsesIdsAndRejectsRootCurrentParentAndDescendantTargets() {
        final WindowsHistoryNativeUiIngressProbe.PartModelSnapshot model = new WindowsHistoryNativeUiIngressProbe.PartModelSnapshot(
            "model-a",
            List.of(
                actorPart("__RootPart__", "Root", Optional.empty(), List.of("parent", "target")),
                actorPart("parent", "Parent", Optional.of("__RootPart__"), List.of("source")),
                actorPart("source", "Source", Optional.of("parent"), List.of("descendant")),
                actorPart("descendant", "Descendant", Optional.of("source"), List.of()),
                actorPart("target", "Target", Optional.of("__RootPart__"), List.of())
            )
        );

        final List<WindowsHistoryNativeUiIngressProbe.PartPair> pairs =
            WindowsHistoryNativeUiIngressProbe.safePartPairs(model);

        assertTrue(pairs.stream().noneMatch(pair -> "__RootPart__".equals(pair.source().id())));
        assertTrue(pairs.stream().noneMatch(pair ->
            "source".equals(pair.source().id())
                && Set.of("parent", "descendant").contains(pair.target().id())));
        assertTrue(pairs.stream().anyMatch(pair ->
            "source".equals(pair.source().id()) && "target".equals(pair.target().id())));
    }

    @Test
    void partActorRejectsAmbiguousNamesAndModelReplacement() {
        final WindowsHistoryNativeUiIngressProbe.PartModelSnapshot ambiguous =
            new WindowsHistoryNativeUiIngressProbe.PartModelSnapshot(
                "model-a",
                List.of(
                    actorPart("__RootPart__", "Root", Optional.empty(), List.of("source", "a", "b")),
                    actorPart("source", "Source", Optional.of("__RootPart__"), List.of()),
                    actorPart("a", "Same", Optional.of("__RootPart__"), List.of()),
                    actorPart("b", "Same", Optional.of("__RootPart__"), List.of())
                )
            );

        assertTrue(WindowsHistoryNativeUiIngressProbe.safePartPairs(ambiguous).isEmpty());
        final WindowsHistoryNativeUiIngressProbe.PartPair pair =
            new WindowsHistoryNativeUiIngressProbe.PartPair(
                "model-a",
                actorPart("source", "Source", Optional.of("__RootPart__"), List.of()),
                actorPart("target", "Target", Optional.of("__RootPart__"), List.of())
            );
        final WindowsHistoryNativeUiIngressProbe.PartModelSnapshot replacement =
            new WindowsHistoryNativeUiIngressProbe.PartModelSnapshot(
                "model-b", pairModelParts(pair));

        assertFalse(WindowsHistoryNativeUiIngressProbe.partPairMatches(replacement, pair));
    }

    @Test
    void partDragNeedsTheSelectedSourceParentTransition() {
        final WindowsHistoryNativeUiIngressProbe.PartModelSnapshot before =
            partModel("model-a", "parent", "source", "target", "other");
        final WindowsHistoryNativeUiIngressProbe.PartPair pair =
            WindowsHistoryNativeUiIngressProbe.safePartPairs(before).stream()
                .filter(candidate -> "source".equals(candidate.source().id())
                    && "target".equals(candidate.target().id()))
                .findFirst()
                .orElseThrow();

        final WindowsHistoryNativeUiIngressProbe.PartDragCheck selectionOnly =
            WindowsHistoryNativeUiIngressProbe.assessPartDrag(
                pair, before, before, "selection", "selection");
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.PartDragStatus.NO_CHANGE,
            selectionOnly.status()
        );

        final WindowsHistoryNativeUiIngressProbe.PartModelSnapshot wrongSource =
            partModel("model-a", "parent", "source", "target", "other-moved");
        final WindowsHistoryNativeUiIngressProbe.PartDragCheck wrong =
            WindowsHistoryNativeUiIngressProbe.assessPartDrag(
                pair, before, wrongSource, "before", "other-source-edit");
        assertEquals(WindowsHistoryNativeUiIngressProbe.PartDragStatus.MISMATCH, wrong.status());
        assertEquals("unselected-significant-change", wrong.code());

        final WindowsHistoryNativeUiIngressProbe.PartModelSnapshot moved =
            partModel("model-a", "target", "source", "target", "other");
        final WindowsHistoryNativeUiIngressProbe.PartDragCheck changed =
            WindowsHistoryNativeUiIngressProbe.assessPartDrag(
                pair, before, moved, "before", "after");
        assertEquals(WindowsHistoryNativeUiIngressProbe.PartDragStatus.CHANGED, changed.status());
        assertEquals(Optional.of("parent"), changed.parentBefore());
        assertEquals(Optional.of("target"), changed.parentAfter());
        assertTrue(changed.evidence().contains("sourceId=source"), changed.evidence());
        assertTrue(changed.evidence().contains("targetId=target"), changed.evidence());

        final WindowsHistoryNativeUiIngressProbe.PartDragCheck sameParent =
            WindowsHistoryNativeUiIngressProbe.assessPartDrag(
                pair, moved, moved, "before", "before");
        assertEquals(WindowsHistoryNativeUiIngressProbe.PartDragStatus.MISMATCH, sameParent.status());
        assertEquals("same-parent", sameParent.code());

        final WindowsHistoryNativeUiIngressProbe.PartDragCheck replaced =
            WindowsHistoryNativeUiIngressProbe.assessPartDrag(
                pair,
                before,
                new WindowsHistoryNativeUiIngressProbe.PartModelSnapshot(
                    "model-b", moved.parts()),
                "before",
                "after");
        assertEquals(WindowsHistoryNativeUiIngressProbe.PartDragStatus.MISMATCH, replaced.status());
        assertEquals("model-changed", replaced.code());
    }

    @Test
    void partRowLocatorRecomputesSourceAfterTargetExpansionAndKeepsPathAndRowAligned() {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        final DefaultMutableTreeNode target = new DefaultMutableTreeNode("Target");
        target.add(new DefaultMutableTreeNode("Target child"));
        final DefaultMutableTreeNode source = new DefaultMutableTreeNode("Source");
        root.add(target);
        root.add(source);
        final JTree tree = new JTree(root);
        final TreePath targetPath = new TreePath(target.getPath());
        final TreePath sourcePath = new TreePath(source.getPath());
        tree.collapsePath(targetPath);
        final int sourceRowBeforeExpansion = tree.getRowForPath(sourcePath);

        final JTable table = new JTable(4, 1);
        table.setRowHeight(20);
        table.setSize(240, 80);
        final WindowsHistoryNativeUiIngressProbe.PartPairLayout layout =
            WindowsHistoryNativeUiIngressProbe.locatePartPairRows(
                table, tree, "Source", "Target");

        assertTrue(layout != null);
        assertTrue(
            layout.source().row() > sourceRowBeforeExpansion,
            "target expansion must displace and force a fresh source row lookup"
        );
        assertEquals(layout.source().row(), tree.getRowForPath(layout.source().path()));
        assertEquals(layout.target().row(), tree.getRowForPath(layout.target().path()));
        assertEquals(layout.source().row(), table.rowAtPoint(layout.source().localPoint()));
        assertEquals(layout.target().row(), table.rowAtPoint(layout.target().localPoint()));
        assertTrue(layout.source().viewport().contains(layout.source().cell()));
        assertTrue(layout.target().viewport().contains(layout.target().cell()));
        assertNull(layout.source().screenPoint(), "headless layout has no stale screen point");
    }

    @Test
    void partRowLocatorRejectsPairsThatCannotShareTheFinalViewport() {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        final DefaultMutableTreeNode target = new DefaultMutableTreeNode("Target");
        target.add(new DefaultMutableTreeNode("Target child"));
        root.add(target);
        root.add(new DefaultMutableTreeNode("middle"));
        root.add(new DefaultMutableTreeNode("another"));
        root.add(new DefaultMutableTreeNode("Source"));
        final JTree tree = new JTree(root);
        tree.collapsePath(new TreePath(target.getPath()));
        final JTable table = new JTable(6, 1);
        table.setRowHeight(20);
        table.setSize(240, 60);

        assertNull(
            WindowsHistoryNativeUiIngressProbe.locatePartPairRows(
                table, tree, "Source", "Target"),
            "the actor must reject a pair that would require drag auto-scroll"
        );
    }

    @Test
    void navigationSettlesFarShorterThanAnEdit() {
        // A long settle on a navigation step is what let the operator's next keystroke land
        // inside the previous step's window.
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.settleMillis(UNDO)
                < WindowsHistoryNativeUiIngressProbe.settleMillis(ACTION),
            "navigation must settle faster than an edit"
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.settleMillis(UNDO),
            WindowsHistoryNativeUiIngressProbe.settleMillis(REDO)
        );
    }

    @Test
    void anEditInsideTheWindowDoesNotHideTheUndoThatFollowsIt() {
        // Measured against the last observed position, not the window's opening one. The operator
        // made an edit inside the native-undo window (position 7 -> 8) and then undid it (8 -> 7):
        // against the opening position of 7 that undo never crosses the baseline and the step
        // never closes, which is what left the r8 run with no verdict at all.
        final String significant = WindowsHistoryNativeUiIngressProbe.significantSequence(
            List.of(entry(0, "正片叠底色 の編集", true))
        );

        assertFalse(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 8L, significant, 7L),
            "an edit inside the window is not the undo"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(UNDO, significant, 7L, significant, 8L),
            "the undo is seen against the position the edit left behind"
        );
        assertTrue(
            WindowsHistoryNativeUiIngressProbe.hasMoved(REDO, significant, 8L, significant, 7L),
            "the same holds for a redo"
        );
    }

    @Test
    void inspectorContentSelectionUsesTheSelectedLowerStackedTab() {
        // Mirrors r77: the dock is one 159x924 column, while the selected Inspector tab is
        // below the upper tool-details pane at about y=530 and its content starts at y=551.
        final Rectangle dock = new Rectangle(100, 66, 159, 924);
        final Rectangle selectedTab = new Rectangle(100, 530, 78, 21);
        final Object upperColumnRoot = new Object();
        final Object upperToolDetails = new Object();
        final Object inspectorContent = new Object();
        final WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate selected =
            WindowsHistoryNativeUiIngressProbe.selectInspectorContentCandidate(
                List.of(
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        upperColumnRoot, new Rectangle(100, 66, 159, 924), true, 100
                    ),
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        upperToolDetails, new Rectangle(100, 66, 159, 459), true, 20
                    ),
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        inspectorContent,
                        new Rectangle(100, selectedTab.y + selectedTab.height, 159, 438),
                        true,
                        8
                    )
                ),
                selectedTab,
                dock
            );

        assertSame(inspectorContent, selected.widget());
    }

    @Test
    void inspectorContentSelectionRejectsHiddenRootAndMissingCandidates() {
        final Rectangle dock = new Rectangle(100, 66, 159, 924);
        final Rectangle selectedTab = new Rectangle(100, 530, 78, 21);
        final Object hiddenRoot = new Object();
        final Object visibleContent = new Object();

        final WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate selected =
            WindowsHistoryNativeUiIngressProbe.selectInspectorContentCandidate(
                List.of(
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        hiddenRoot, new Rectangle(100, 551, 159, 438), false, 100
                    ),
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        visibleContent, new Rectangle(100, 551, 159, 438), true, 4
                    )
                ),
                selectedTab,
                dock
            );

        assertSame(visibleContent, selected.widget(), "a hidden high-score root is not usable");
        assertNull(
            WindowsHistoryNativeUiIngressProbe.selectInspectorContentCandidate(
                List.of(
                    new WindowsHistoryNativeUiIngressProbe.InspectorContentCandidate(
                        hiddenRoot, new Rectangle(100, 551, 159, 438), false, 100
                    )
                ),
                selectedTab,
                dock
            ),
            "no visible candidate must leave the inspector root unresolved"
        );
    }

    @Test
    void menuNavigationFallsBackOnlyAfterMeasuredUnchangedSamples() {
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.DELIVERED,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", 8L, 7L)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.WRONG_DIRECTION,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", 8L, 9L),
            "a shortcut that moved the cursor the wrong way still must not be repeated"
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.FALLBACK,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", 8L, 8L)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.DELIVERED,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("REDO", 7L, 8L)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.WRONG_DIRECTION,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("REDO", 8L, 7L)
        );
    }

    @Test
    void menuNavigationDoesNotFallbackWhenPositionEvidenceIsUnknown() {
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.UNKNOWN,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", -1L, List.of(7L))
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution(
                "UNDO", 8L, Arrays.asList(null, null)
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution(
                "UNDO", 8L, Arrays.asList(null, 8L)
            ),
            "one unavailable sample still leaves a possible menu action unobserved"
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ShortcutResolution.FALLBACK,
            WindowsHistoryNativeUiIngressProbe.shortcutResolution("UNDO", 8L, List.of(8L, 8L)),
            "only measured unchanged samples authorize Robot fallback"
        );
    }

    @Test
    void parameterValueChangeIsEvidenceEvenWhenHistoryAdmissionIsNotObserved() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 0.0f))
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 12.5f))
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
            WindowsHistoryNativeUiIngressProbe.compareParameterState(before, after)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterHistoryAdmission.NOT_OBSERVED,
            WindowsHistoryNativeUiIngressProbe.parameterHistoryAdmission("", ""),
            "a changed value is not relabelled as unchanged just because no significant Undo entry appeared"
        );
    }

    @Test
    void parameterValueEvidenceRejectsAnUnchangedReadback() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 0.0f))
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 0.0f))
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNCHANGED,
            WindowsHistoryNativeUiIngressProbe.compareParameterState(before, after)
        );
    }

    @Test
    void parameterReadbackFailsClosedForModelSwitchAndUnavailableReads() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-A",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 0.0f))
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot switched =
            WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
                "model-B",
                List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample("ParamAngleX", 12.5f))
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.MODEL_CHANGED,
            WindowsHistoryNativeUiIngressProbe.compareParameterState(before, switched)
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.compareParameterState(
                before,
                WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.unavailable("read-failed")
            )
        );
    }

    @Test
    void parameterLifecycleEvidenceKeepsRelatedAndUnrelatedCallbacksSeparate() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "EDT"),
            lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback"),
            lifecycle(4L, "on", "ParamOpacity", 1.0f, 0.8f, "callback")
        );

        assertEquals(
            3,
            WindowsHistoryNativeUiIngressProbe.relevantParameterLifecycle(
                callbacks, Set.of("ParamAngleX")
            ).size()
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.COMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleX"), true
            )
        );
        assertTrue(
            callbacks.get(1).json(Set.of("ParamAngleX")).contains("\"sequence\":2"),
            "callback sequence must be retained for evidence review"
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.OBSERVED_UNRELATED,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(lifecycle(1L, "on", "ParamOpacity", 1.0f, 0.8f, "callback")),
                Set.of(),
                false
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.MISSING,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(),
                Set.of("ParamAngleX"),
                true
            )
        );

        final WindowsHistoryNativeUiIngressProbe probe =
            new WindowsHistoryNativeUiIngressProbe();
        assertEquals(
            2.5f,
            probe.beforeSetParameterValue(null, 2.5f),
            0.0f,
            "before callback must return the requested value unchanged"
        );
    }

    @Test
    void parameterLifecycleAcceptsAContinuousNativeValueChain() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(2L, "on", "ParamAngleX", 0.0f, 5.0f, "callback"),
            lifecycle(3L, "on", "ParamAngleX", 5.0f, 12.5f, "callback"),
            lifecycle(4L, "after", "ParamAngleX", null, 12.5f, "callback")
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.COMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleX"), true
            )
        );
    }

    @Test
    void parameterLifecycleAcceptsR79TraceWithTrailingNoChangeSegment() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "41f72ac0-bd7f-4431-8011-be1ee7131e8a", "ParamAngleY", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "41f72ac0-bd7f-4431-8011-be1ee7131e8a", "ParamAngleY", 1.4399999f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleY", 0.0f, 0.06f, "AWT-EventQueue-0"),
            lifecycle(
                2L, "on", "ParamAngleY", 0.0f, 0.06f,
                "bulkhead-dev.turboism.validation.history-native-ui-1"
            ),
            lifecycle(
                3L, "after", "ParamAngleY", null, 0.06f,
                "bulkhead-dev.turboism.validation.history-native-ui-1"
            ),
            lifecycle(4L, "before", "ParamAngleY", 0.06f, 0.29999998f, "AWT-EventQueue-0"),
            lifecycle(
                5L, "on", "ParamAngleY", 0.06f, 0.29999998f,
                "bulkhead-dev.turboism.validation.history-native-ui-1"
            ),
            lifecycle(
                6L, "after", "ParamAngleY", null, 0.29999998f,
                "bulkhead-dev.turboism.validation.history-native-ui-1"
            ),
            lifecycle(7L, "before", "ParamAngleY", 0.29999998f, 1.4399999f, "AWT-EventQueue-0"),
            lifecycle(
                8L, "on", "ParamAngleY", 0.29999998f, 1.4399999f,
                "bulkhead-dev.turboism.validation.history-native-ui-1"
            ),
            lifecycle(
                9L, "after", "ParamAngleY", null, 1.4399999f,
                "bulkhead-dev.turboism.validation.history-native-ui-1"
            ),
            lifecycle(10L, "before", "ParamAngleY", 1.4399999f, 1.4399999f, "AWT-EventQueue-0"),
            lifecycle(
                11L, "after", "ParamAngleY", null, 1.4399999f,
                "bulkhead-dev.turboism.validation.history-native-ui-1"
            )
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.COMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleY"), true
            )
        );
    }

    @Test
    void parameterLifecyclePureNoChangeCannotSucceed() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", "ParamAngleY", 1.4399999f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", "ParamAngleY", 1.4399999f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleY", 1.4399999f, 1.4399999f, "AWT-EventQueue-0"),
            lifecycle(
                2L, "after", "ParamAngleY", null, 1.4399999f,
                "bulkhead-dev.turboism.validation.history-native-ui-1"
            )
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.OBSERVED_UNRELATED,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of(), false
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleY"), false
            )
        );
    }

    @Test
    void parameterLifecycleNeutralSegmentCannotHideMissingOn() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", "ParamAngleY", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", "ParamAngleY", 5.0f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleY", 0.0f, 5.0f, "AWT-EventQueue-0"),
            lifecycle(2L, "on", "ParamAngleY", 0.0f, 5.0f, "callback"),
            lifecycle(3L, "after", "ParamAngleY", null, 5.0f, "callback"),
            lifecycle(4L, "before", "ParamAngleY", 5.0f, 9.0f, "AWT-EventQueue-0"),
            lifecycle(5L, "after", "ParamAngleY", null, 5.0f, "callback")
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleY"), true
            )
        );
    }

    @Test
    void parameterLifecycleRejectsReversedAndUnrelatedValues() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "after", "ParamAngleX", null, 999.0f, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 999.0f, 999.0f, "callback"),
                    lifecycle(3L, "before", "ParamAngleX", null, null, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 0.0f, 999.0f, "callback"),
                    lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
    }

    @Test
    void parameterLifecycleRejectsNoChangeMissingPhaseAndMissingValues() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 0.0f, 0.0f, "callback"),
                    lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(2L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", null, null, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            )
        );
    }

    @Test
    void parameterLifecycleWithoutReadbackCannotBeComplete() {
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, Set.of("ParamAngleX"), true
            )
        );
    }

    @Test
    void parameterLifecycleSampleLossIsExplicitlyIncomplete() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);
        final List<WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent> callbacks = List.of(
            lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
            lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
        );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.INCOMPLETE,
            WindowsHistoryNativeUiIngressProbe.parameterLifecycleStatus(
                callbacks, before, after, Set.of("ParamAngleX"), true, false
            )
        );
    }

    @Test
    void parameterLifecycleReportsCallbackModelCorrelationAsUnavailable() {
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot before = parameterSnapshot(
            "model-A", 0.0f);
        final WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot after = parameterSnapshot(
            "model-A", 12.5f);
        final WindowsHistoryNativeUiIngressProbe.ParameterLifecycleAssessment assessment =
            WindowsHistoryNativeUiIngressProbe.assessParameterLifecycle(
                List.of(
                    lifecycle(1L, "before", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(2L, "on", "ParamAngleX", 0.0f, 12.5f, "callback"),
                    lifecycle(3L, "after", "ParamAngleX", null, 12.5f, "callback")
                ),
                before,
                after,
                Set.of("ParamAngleX"),
                true
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterLifecycleStatus.COMPLETE,
            assessment.status()
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterModelCorrelation.UNAVAILABLE,
            assessment.modelCorrelation()
        );
    }

    @Test
    void parameterActorModelFailureCannotBeRecoveredByALaterNormalRead() {
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation actorFailure =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.MODEL_CHANGED,
                WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.unavailable("model-changed"),
                "model-changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation laterChange =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
                parameterSnapshot("model-B", 12.5f),
                "changed"
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.MODEL_CHANGED,
            WindowsHistoryNativeUiIngressProbe.settleParameterObservations(
                actorFailure, List.of(laterChange)
            ).outcome()
        );

        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation unavailable =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNAVAILABLE,
                WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.unavailable("read-failed"),
                "read-failed"
            );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.settleParameterObservations(
                unavailable, List.of(laterChange)
            ).outcome()
        );
    }

    @Test
    void parameterSettleFailureCannotBeRecoveredByALaterNormalRead() {
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation actorChange =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
                parameterSnapshot("model-A", 5.0f),
                "changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation laterChange =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
                parameterSnapshot("model-A", 12.5f),
                "changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation modelChanged =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.MODEL_CHANGED,
                WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.unavailable(
                    "model-changed"
                ),
                "model-changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation unavailable =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNAVAILABLE,
                WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.unavailable(
                    "read-failed"
                ),
                "read-failed"
            );

        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.MODEL_CHANGED,
            WindowsHistoryNativeUiIngressProbe.settleParameterObservations(
                actorChange, List.of(modelChanged, laterChange)
            ).outcome()
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNAVAILABLE,
            WindowsHistoryNativeUiIngressProbe.settleParameterObservations(
                actorChange, List.of(unavailable, laterChange)
            ).outcome()
        );
    }

    @Test
    void parameterSettleUsesTheLatestSuccessfulReadbackAsFinalState() {
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation actorChange =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
                parameterSnapshot("model-A", 5.0f),
                "changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation settledChange =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.CHANGED,
                parameterSnapshot("model-A", 12.5f),
                "changed"
            );
        final WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation unchanged =
            new WindowsHistoryNativeUiIngressProbe.ParameterChangeObservation(
                WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNCHANGED,
                parameterSnapshot("model-A", 0.0f),
                "unchanged"
            );

        assertEquals(
            12.5f,
            WindowsHistoryNativeUiIngressProbe.settleParameterObservations(
                actorChange, List.of(settledChange)
            ).after().values().get(0).value(),
            0.0f
        );
        assertEquals(
            WindowsHistoryNativeUiIngressProbe.ParameterStateOutcome.UNCHANGED,
            WindowsHistoryNativeUiIngressProbe.settleParameterObservations(
                actorChange, List.of(unchanged)
            ).outcome()
        );
        assertEquals(
            0.0f,
            WindowsHistoryNativeUiIngressProbe.settleParameterObservations(
                actorChange, List.of(unchanged)
            ).after().values().get(0).value(),
            0.0f
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot parameterSnapshot(
        final String modelId,
        final float value
    ) {
        return parameterSnapshot(modelId, "ParamAngleX", value);
    }

    private static WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot parameterSnapshot(
        final String modelId,
        final String parameterId,
        final float value
    ) {
        return WindowsHistoryNativeUiIngressProbe.ParameterStateSnapshot.available(
            modelId,
            List.of(new WindowsHistoryNativeUiIngressProbe.ParameterValueSample(parameterId, value))
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent lifecycle(
        final String phase,
        final String parameterId,
        final Float oldValue,
        final Float newValue,
        final String thread
    ) {
        return lifecycle(0L, phase, parameterId, oldValue, newValue, thread);
    }

    private static WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent lifecycle(
        final long sequence,
        final String phase,
        final String parameterId,
        final Float oldValue,
        final Float newValue,
        final String thread
    ) {
        return new WindowsHistoryNativeUiIngressProbe.ParameterLifecycleEvent(
            sequence, phase, parameterId, oldValue, newValue, thread
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.Verdict partVerdict(
        final HistoryEntryDetail detail
    ) {
        return partVerdict(List.of(detail));
    }

    private static WindowsHistoryNativeUiIngressProbe.ActorPart actorPart(
        final String id,
        final String name,
        final Optional<String> parentId,
        final List<String> childIds
    ) {
        return new WindowsHistoryNativeUiIngressProbe.ActorPart(id, name, parentId, childIds);
    }

    private static List<WindowsHistoryNativeUiIngressProbe.ActorPart> pairModelParts(
        final WindowsHistoryNativeUiIngressProbe.PartPair pair
    ) {
        return List.of(
            actorPart("__RootPart__", "Root", Optional.empty(),
                List.of(pair.source().id(), pair.target().id())),
            pair.source(),
            pair.target()
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.PartModelSnapshot partModel(
        final String modelId,
        final String sourceParent,
        final String sourceId,
        final String targetId,
        final String otherId
    ) {
        final List<String> rootChildren = new java.util.ArrayList<>(
            List.of("parent", targetId, otherId));
        rootChildren.remove(sourceParent);
        if (!rootChildren.contains(sourceParent)) rootChildren.add(0, sourceParent);
        final List<String> parentChildren = "parent".equals(sourceParent)
            ? List.of(sourceId) : List.of();
        final List<String> targetChildren = targetId.equals(sourceParent)
            ? List.of(sourceId) : List.of();
        return new WindowsHistoryNativeUiIngressProbe.PartModelSnapshot(
            modelId,
            List.of(
                actorPart("__RootPart__", "Root", Optional.empty(), rootChildren),
                actorPart("parent", "Parent", Optional.of("__RootPart__"), parentChildren),
                actorPart(sourceId, "Source", Optional.of(sourceParent), List.of()),
                actorPart(targetId, "Target", Optional.of("__RootPart__"), targetChildren),
                actorPart(otherId, "Other", Optional.of("__RootPart__"), List.of())
            )
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.Verdict partVerdict(
        final List<HistoryEntryDetail> details
    ) {
        final WindowsHistoryManagerValidationProbe.SdkEntry old =
            sdkEntry(0, "old", HistoryEntryDetail.labelOnly("old"));
        final java.util.ArrayList<WindowsHistoryManagerValidationProbe.SdkEntry> after =
            new java.util.ArrayList<>();
        after.add(old);
        for (int index = 0; index < details.size(); index++) {
            after.add(sdkEntry(index + 1, "new-" + index, details.get(index)));
        }
        return WindowsHistoryNativeUiIngressProbe.checkPartMembershipStep(
            sdkHistory(List.of(old), 1),
            sdkHistory(after, after.size())
        );
    }

    private static WindowsHistoryManagerValidationProbe.SdkHistorySnapshot sdkHistory(
        final List<WindowsHistoryManagerValidationProbe.SdkEntry> entries,
        final int position
    ) {
        return new WindowsHistoryManagerValidationProbe.SdkHistorySnapshot(
            "AVAILABLE",
            7L,
            1L,
            position,
            position > 0,
            false,
            "document-1",
            "manager-1",
            entries.size(),
            List.copyOf(entries)
        );
    }

    private static WindowsHistoryManagerValidationProbe.SdkEntry sdkEntry(
        final int index,
        final String entryId,
        final HistoryEntryDetail detail
    ) {
        return new WindowsHistoryManagerValidationProbe.SdkEntry(
            index,
            entryId,
            "entry-" + index,
            WindowsHistoryManagerValidationProbe.sdkDetailJson(detail, 0),
            detail
        );
    }

    private static HistoryEntryDetail membershipDetail(
        final HistoryRelationChange.Endpoint before,
        final HistoryRelationChange.Endpoint after,
        final HistoryAction.DetailLevel level
    ) {
        return relationDetail(
            new HistoryRelationChange(HistoryRelationChange.Kind.PART_MEMBERSHIP, before, after),
            level
        );
    }

    private static HistoryEntryDetail relationDetail(
        final HistoryRelationChange relation,
        final HistoryAction.DetailLevel level
    ) {
        final HistoryChange change = relationChange(relation);
        return new HistoryEntryDetail(
            "membership",
            level,
            HistoryOrigin.hostUnattributed(),
            List.of(childTarget()),
            List.of(change),
            Optional.empty(),
            level == HistoryAction.DetailLevel.FULL
                ? Optional.empty()
                : Optional.of("fixture.partial")
        );
    }

    private static HistoryEntryDetail missingRelationDetail() {
        final HistoryChange change = new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()),
            Optional.empty()
        );
        return new HistoryEntryDetail(
            "missing relation",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(childTarget()),
            List.of(change),
            Optional.empty(),
            Optional.of("fixture.missing-relation")
        );
    }

    private static HistoryChange relationChange(final HistoryRelationChange relation) {
        return new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()),
            Optional.of(relation)
        );
    }

    private static HistoryTarget childTarget() {
        return new HistoryTarget(
            "ART_MESH", Optional.of("child-1"), Optional.of("Child")
        );
    }

    private static HistoryTarget target(final String type, final String id, final String name) {
        return new HistoryTarget(type, Optional.of(id), Optional.of(name));
    }

    private static HistoryRelationChange.Endpoint targetEndpoint(
        final String type,
        final String id,
        final String name
    ) {
        return targetEndpoint(target(type, id, name));
    }

    private static HistoryRelationChange.Endpoint targetEndpoint(final HistoryTarget target) {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.TARGET,
            Optional.of(target)
        );
    }

    private static HistoryRelationChange.Endpoint rootEndpoint() {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.ROOT,
            Optional.empty()
        );
    }

    private static HistoryRelationChange.Endpoint unknownEndpoint() {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.UNKNOWN,
            Optional.empty()
        );
    }

    private static WindowsHistoryManagerValidationProbe.Entry entry(
        final int index,
        final String label,
        final boolean significant
    ) {
        return new WindowsHistoryManagerValidationProbe.Entry(
            index,
            label,
            significant,
            WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                "com.live2d.undo.GroupUndo",
                "LIMIT",
                "history.detail.node-or-depth-limit"
            )
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.Observed event(
        final String phase,
        final long sequence,
        final String origin
    ) {
        return event(phase, sequence, origin, "Add Part");
    }

    private static WindowsHistoryNativeUiIngressProbe.Observed event(
        final String phase,
        final long sequence,
        final String origin,
        final String label
    ) {
        return eventWithFields(
            phase,
            sequence,
            "EXECUTE_EDITOR_COMMAND",
            origin,
            "",
            label
        );
    }

    private static WindowsHistoryNativeUiIngressProbe.Observed navigationEvent(
        final String phase,
        final long sequence,
        final String kind
    ) {
        return navigationEvent(phase, sequence, kind, kind);
    }

    private static WindowsHistoryNativeUiIngressProbe.Observed navigationEvent(
        final String phase,
        final long sequence,
        final String origin,
        final String operation
    ) {
        return eventWithFields(phase, sequence, operation, origin, "", "");
    }

    private static WindowsHistoryNativeUiIngressProbe.Observed eventWithFields(
        final String phase,
        final long sequence,
        final String operation,
        final String origin,
        final String subjectId,
        final String label
    ) {
        return new WindowsHistoryNativeUiIngressProbe.Observed(
            phase,
            sequence,
            operation,
            origin,
            subjectId,
            label,
            "AWT-EventQueue-0",
            "2026-01-01T00:00:00Z"
        );
    }
}
