package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.topology.ValidationError;
import java.util.List;

/**
 * {@code ERR-030}: a document failed validation.
 *
 * <p>The condition carries every error rather than the first, under {@code ERR-030}, so an operator
 * repairs a document in one pass.
 */
public final class InvalidTopologyException extends TopologyException {

    private static final long serialVersionUID = 1L;

    private final transient List<ValidationError> errors;

    /** The condition, with every error the document carried. */
    public InvalidTopologyException(List<ValidationError> errors) {
        super(ErrorCode.INVALID_TOPOLOGY, null,
                "the document carried " + errors.size() + " validation error(s)");
        this.errors = List.copyOf(errors);
    }

    /** Every error the document carried, in the order the rules read them. */
    public List<ValidationError> errors() {
        return errors;
    }
}
