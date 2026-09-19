package com.codeheadsystems.sharder.core.internal.migrate;

import com.codeheadsystems.sharder.NodeId;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One shard's move from a source node to a destination node.
 *
 * <p>The coordinator sequences the move and the integrator's hooks perform it. The library moves no
 * data and speaks no storage protocol: what it owns is the order of the states and the rules about
 * when each may be left.
 */
public final class Handoff {

    private final String id;
    private final String shard;
    private final NodeId source;
    private final NodeId destination;
    private final long fromEpoch;
    private long toEpoch;
    private HandoffState state = HandoffState.PLANNED;
    private String failureKind;
    private long quiesceInstant = -1;
    private long leaseMillis;

    Handoff(String id, String shard, NodeId source, NodeId destination, long fromEpoch,
            long toEpoch) {
        this.id = id;
        this.shard = shard;
        this.source = source;
        this.destination = destination;
        this.fromEpoch = fromEpoch;
        this.toEpoch = toEpoch;
    }

    /** The handoff's identifier within its plan. */
    public String id() {
        return id;
    }

    /** The shard whose ownership moves. */
    public String shard() {
        return shard;
    }

    /** The node that owns the shard before the cutover. */
    public NodeId source() {
        return source;
    }

    /** The node that owns it after. */
    public NodeId destination() {
        return destination;
    }

    /** The epoch the plan was built from. */
    public long fromEpoch() {
        return fromEpoch;
    }

    /** The epoch the plan targets, which a resumption at {@code verifying} may move. */
    public long toEpoch() {
        return toEpoch;
    }

    /** The state the handoff holds. */
    public HandoffState state() {
        return state;
    }

    /** The failure kind of {@code MOVE-011}, where the handoff failed. */
    public Optional<String> failureKind() {
        return Optional.ofNullable(failureKind);
    }

    /** The instant the source quiesced, where it has. */
    public OptionalLong quiesceInstant() {
        return quiesceInstant < 0 ? OptionalLong.empty() : OptionalLong.of(quiesceInstant);
    }

    /** The lease the source granted at that instant. */
    public long leaseMillis() {
        return leaseMillis;
    }

    void moveTo(HandoffState next) {
        this.state = next;
        if (next != HandoffState.FAILED) {
            this.failureKind = null;
        }
    }

    void fail(String kind) {
        this.state = HandoffState.FAILED;
        this.failureKind = kind;
    }

    void quiesced(long at, long lease) {
        this.quiesceInstant = at;
        this.leaseMillis = lease;
    }

    void targetEpoch(long epoch) {
        this.toEpoch = epoch;
    }
}
