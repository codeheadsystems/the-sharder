package com.codeheadsystems.sharder.error;

import com.codeheadsystems.sharder.ShardId;
import com.codeheadsystems.sharder.topology.FencingToken;

/**
 * The conditions of the topology block, which are the codes from 201 to 204 of {@code ERR-010}.
 *
 * <p>None of them is raised out of a document the load pipeline read on its own, under the
 * conditions that never raise: a document refused there is ordinary operation and no caller is
 * waiting on it. Each is raised out of {@code refresh()} and out of
 * {@code TopologyLoader.validate()}, because both are calls an integrator made.
 */
public sealed abstract class TopologyException extends SharderException
        permits InvalidTopologyException, TopologyConflictException, StaleDocumentException,
                ProviderException {

    private static final long serialVersionUID = 1L;

    TopologyException(ErrorCode errorCode, String reason, String message) {
        super(errorCode, reason, message);
    }

    TopologyException(ErrorCode errorCode, String reason, String message, FencingToken token,
                      ShardId shard, Throwable original) {
        super(errorCode, reason, message, token, shard, original);
    }
}
