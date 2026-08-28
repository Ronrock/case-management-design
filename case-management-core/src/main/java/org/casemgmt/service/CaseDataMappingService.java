package org.casemgmt.service;

import java.util.List;
import java.util.Map;

/** Maps approved engine variables into conflict-aware canonical patches. */
public interface CaseDataMappingService {

    CanonicalPatch mapTaskOutput(String caseId, String taskDefinitionKey,
                                 Map<String, Object> engineVariables);

    PatchResult apply(CanonicalPatch patch);

    enum PatchStatus { APPLIED, NO_CHANGES, CONFLICT }

    record PatchResult(PatchStatus status, long caseVersion, ConflictMetadata conflict) {
        public static PatchResult applied(long caseVersion) {
            return new PatchResult(PatchStatus.APPLIED, caseVersion, null);
        }

        public static PatchResult noChanges(long caseVersion) {
            return new PatchResult(PatchStatus.NO_CHANGES, caseVersion, null);
        }

        public static PatchResult conflict(long caseVersion, ConflictMetadata conflict) {
            return new PatchResult(PatchStatus.CONFLICT, caseVersion, conflict);
        }
    }

    record ConflictMetadata(long expectedCaseVersion, long actualCaseVersion,
                            List<FieldConflict> fields) {
        public ConflictMetadata {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** Values are replaced with {@link CanonicalPatch#REDACTED} for sensitive fields. */
    record FieldConflict(String fieldId, Object expectedValue, Object actualValue,
                         boolean redacted) { }
}
