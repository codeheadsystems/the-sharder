package com.codeheadsystems.sharder.api;

import com.codeheadsystems.sharder.migrate.CatchUpResult;
import com.codeheadsystems.sharder.migrate.CutoverRecord;
import com.codeheadsystems.sharder.migrate.CutoverResult;
import com.codeheadsystems.sharder.migrate.HandoffContext;
import com.codeheadsystems.sharder.migrate.HookDeclaration;
import com.codeheadsystems.sharder.migrate.HookResult;
import com.codeheadsystems.sharder.migrate.MovementHooks;
import com.codeheadsystems.sharder.migrate.Observation;
import com.codeheadsystems.sharder.migrate.ObserveResult;
import com.codeheadsystems.sharder.migrate.QuiesceResult;
import com.codeheadsystems.sharder.migrate.TransferResult;
import com.codeheadsystems.sharder.migrate.VerifyResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Movement hooks that move nothing and record what they were asked, for the plan tests. */
final class RecordingHooks implements MovementHooks {

    private final List<String> calls = new ArrayList<>();
    private final Map<String, CutoverRecord> committed = new LinkedHashMap<>();
    private HookDeclaration declaration = HookDeclaration.of("rows");
    private long bulkRemaining = 0;
    private long residue = 0;
    private int leaseMillis = 120000;
    private HookResult prepareResult = HookResult.success();
    private CutoverResult cutoverResult;
    private VerifyResult verifyResult = new VerifyResult.Matched();
    private ObserveResult observeResult;

    /** What the hooks were asked, in order. */
    List<String> calls() {
        return List.copyOf(calls);
    }

    /** The records the hooks committed, by shard. */
    Map<String, CutoverRecord> committed() {
        return Map.copyOf(committed);
    }

    RecordingHooks declaring(HookDeclaration value) {
        this.declaration = value;
        return this;
    }

    RecordingHooks preparing(HookResult value) {
        this.prepareResult = value;
        return this;
    }

    RecordingHooks bulkRemaining(long value) {
        this.bulkRemaining = value;
        return this;
    }

    RecordingHooks residue(long value) {
        this.residue = value;
        return this;
    }

    RecordingHooks lease(int value) {
        this.leaseMillis = value;
        return this;
    }

    RecordingHooks committing(CutoverResult value) {
        this.cutoverResult = value;
        return this;
    }

    RecordingHooks verifying(VerifyResult value) {
        this.verifyResult = value;
        return this;
    }

    RecordingHooks observing(ObserveResult value) {
        this.observeResult = value;
        return this;
    }

    @Override
    public HookDeclaration declare() {
        return declaration;
    }

    @Override
    public HookResult prepare(HandoffContext context) {
        calls.add("prepare:" + context.shardId().asText());
        return prepareResult;
    }

    @Override
    public TransferResult transfer(HandoffContext context, int budget) {
        calls.add("transfer:" + context.shardId().asText() + ":" + budget);
        return new TransferResult(HookResult.success(), budget, bulkRemaining);
    }

    @Override
    public CatchUpResult catchUp(HandoffContext context, int budget) {
        calls.add("catchUp:" + context.shardId().asText() + ":" + budget);
        return new CatchUpResult(HookResult.success(), budget, residue);
    }

    @Override
    public QuiesceResult quiesce(HandoffContext context) {
        calls.add("quiesce:" + context.shardId().asText());
        return QuiesceResult.of(leaseMillis);
    }

    @Override
    public CutoverResult commitCutover(HandoffContext context) {
        calls.add("commitCutover:" + context.shardId().asText());
        if (cutoverResult != null) {
            return cutoverResult;
        }
        CutoverRecord record = CutoverRecord.of(context.shardId(), context.topologyId(),
                context.toEpoch(), context.destination());
        committed.put(context.shardId().asText(), record);
        return new CutoverResult.Committed(record);
    }

    @Override
    public VerifyResult verify(HandoffContext context) {
        calls.add("verify:" + context.shardId().asText());
        return verifyResult;
    }

    @Override
    public HookResult cleanup(HandoffContext context) {
        calls.add("cleanup:" + context.shardId().asText());
        return HookResult.success();
    }

    @Override
    public HookResult rollback(HandoffContext context) {
        calls.add("rollback:" + context.shardId().asText());
        return HookResult.success();
    }

    @Override
    public ObserveResult observe(HandoffContext context) {
        calls.add("observe:" + context.shardId().asText());
        if (observeResult != null) {
            return observeResult;
        }
        return new ObserveResult.Observed(new Observation(
                Optional.ofNullable(committed.get(context.shardId().asText())), true, false,
                false));
    }
}
