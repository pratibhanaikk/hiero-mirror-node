// SPDX-License-Identifier: Apache-2.0

package org.hiero.mirror.web3.common;

import com.hedera.hapi.node.state.common.EntityNumber;
import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hiero.mirror.common.domain.contract.ContractAction;
import org.hiero.mirror.common.domain.transaction.RecordFile;
import org.hiero.mirror.web3.evm.contracts.execution.traceability.Opcode;
import org.hiero.mirror.web3.evm.contracts.execution.traceability.OpcodeTracerOptions;
import org.hiero.mirror.web3.evm.store.CachingStateFrame;
import org.hiero.mirror.web3.evm.store.StackedStateFrames;
import org.hiero.mirror.web3.service.model.CallServiceParameters;
import org.hiero.mirror.web3.viewmodel.BlockType;

@SuppressWarnings("deprecation")
@Getter
public class ContractCallContext {

    public static final String CONTEXT_NAME = "ContractCallContext";
    private static final ScopedValue<ContractCallContext> SCOPED_VALUE = ScopedValue.newInstance();

    @Getter(AccessLevel.NONE)
    private final Map<String, Map<Object, Object>> readCache = new HashMap<>();

    @Getter
    private final long startTime = System.currentTimeMillis();

    @Getter(AccessLevel.NONE)
    private final Map<String, Map<Object, Object>> writeCache = new HashMap<>();

    @Setter
    private List<ContractAction> contractActions = List.of();

    @Setter
    private OpcodeTracerOptions opcodeTracerOptions;

    @Setter
    private List<Opcode> opcodes = new ArrayList<>();

    @Setter
    private CallServiceParameters callServiceParameters;

    /**
     * Record file which stores the block timestamp and other historical block details used for filtering of historical
     * data.
     */
    @Setter
    private RecordFile recordFile;

    @Setter
    private EntityNumber entityNumber;
    /** Current top of stack (which is all linked together) */
    private CachingStateFrame<Object> stack;

    /** Fixed "base" of stack: a R/O cache frame on top of the DB-backed cache frame */
    private CachingStateFrame<Object> stackBase;

    /**
     * The timestamp used to fetch the state from the stackedStateFrames.
     */
    @Setter
    private Optional<Long> timestamp = Optional.empty();

    @Setter
    private boolean isBalanceCall;

    @Setter
    private long gasRequirement;

    private ContractCallContext() {}

    public static ContractCallContext get() {
        return SCOPED_VALUE.get();
    }

    public static boolean isInitialized() {
        return SCOPED_VALUE.isBound();
    }

    public static <T> T run(Function<ContractCallContext, T> function) {
        return ScopedValue.getWhere(SCOPED_VALUE, new ContractCallContext(), () -> function.apply(SCOPED_VALUE.get()));
    }

    public void reset() {
        stack = stackBase;
        writeCache.clear();
    }

    public int getStackHeight() {
        return stack.height() - stackBase.height();
    }

    public void setStack(CachingStateFrame<Object> stack) {
        this.stack = stack;
        if (stackBase == null) {
            stackBase = stack;
        }
    }

    public void updateStackFromUpstream() {
        if (stack == stackBase) {
            throw new EmptyStackException();
        }
        setStack(stack.getUpstream().orElseThrow(EmptyStackException::new));
    }

    public void addOpcodes(Opcode opcode) {
        opcodes.add(opcode);
    }

    /**
     * Chop the stack back to its base. This keeps the most-upstream-layer which connects to the database, and the
     * `ROCachingStateFrame` on top of it.  Therefore, everything already read from the database is still present,
     * unchanged, in the stacked cache.  (Usage case is the multiple calls to `eth_estimateGas` in order to "binary
     * search" to the closest gas approximation for a given contract call: The _first_ call is the only one that
     * actually hits the database (via the database accessors), all subsequent executions will fetch the same values
     * (required!) from the RO-cache without touching the database again - if you cut back the stack between executions
     * using this method.)
     */
    public void initializeStackFrames(final StackedStateFrames stackedStateFrames) {
        if (stackedStateFrames != null) {
            final var stateTimestamp = getTimestampOrDefaultFromRecordFile();
            stackBase = stack = stackedStateFrames.getInitializedStackBase(stateTimestamp);
        }
    }

    public boolean useHistorical() {
        if (callServiceParameters != null) {
            return callServiceParameters.getBlock() != BlockType.LATEST;
        }
        return recordFile != null; // Remove recordFile comparison after mono code deletion
    }

    /**
     * Returns the set timestamp or the consensus end timestamp from the set record file only if we are in a historical
     * context. If not - an empty optional is returned.
     */
    public Optional<Long> getTimestamp() {
        if (useHistorical()) {
            return getTimestampOrDefaultFromRecordFile();
        }
        return Optional.empty();
    }

    private Optional<Long> getTimestampOrDefaultFromRecordFile() {
        return timestamp.or(() -> Optional.ofNullable(recordFile).map(RecordFile::getConsensusEnd));
    }

    public Map<Object, Object> getReadCacheState(final String stateKey) {
        return readCache.computeIfAbsent(stateKey, k -> new HashMap<>());
    }

    public Map<Object, Object> getWriteCacheState(final String stateKey) {
        return writeCache.computeIfAbsent(stateKey, k -> new HashMap<>());
    }
}
