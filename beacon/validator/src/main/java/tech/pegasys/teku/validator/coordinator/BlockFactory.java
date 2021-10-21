/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.validator.coordinator;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.executionengine.ExecutionEngineChannel;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.EpochProcessingException;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.SlotProcessingException;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.StateTransitionException;

public class BlockFactory {
  private final Spec spec;
  private final ExecutionEngineChannel executionEngineChannel;
  private final BlockOperationSelectorFactory operationSelector;

  public BlockFactory(
      final Spec spec,
      final ExecutionEngineChannel executionEngineChannel,
      final BlockOperationSelectorFactory operationSelector) {
    this.spec = spec;
    this.executionEngineChannel = executionEngineChannel;
    this.operationSelector = operationSelector;
  }

  public BeaconBlock createUnsignedBlock(
      final BeaconState previousState,
      final Optional<BeaconState> maybeBlockSlotState,
      final UInt64 newSlot,
      final BLSSignature randaoReveal,
      final Optional<Bytes32> optionalGraffiti)
      throws EpochProcessingException, SlotProcessingException, StateTransitionException {
    checkArgument(
        maybeBlockSlotState.isEmpty() || maybeBlockSlotState.get().getSlot().equals(newSlot),
        "Block slot state for slot %s but should be for slot %s",
        maybeBlockSlotState.map(BeaconState::getSlot).orElse(null),
        newSlot);

    // Process empty slots up to the one before the new block slot
    final UInt64 slotBeforeBlock = newSlot.minus(UInt64.ONE);

    // If needed, process the slot transition into the slot for the block
    final BeaconState blockSlotState;
    if (maybeBlockSlotState.isPresent()) {
      blockSlotState = maybeBlockSlotState.get();
    } else {
      BeaconState blockPreState;
      if (previousState.getSlot().equals(slotBeforeBlock)) {
        blockPreState = previousState;
      } else {
        blockPreState = spec.processSlots(previousState, slotBeforeBlock, executionEngineChannel);
      }
      blockSlotState = spec.processSlots(blockPreState, newSlot, executionEngineChannel);
    }

    final Bytes32 parentRoot = spec.getBlockRootAtSlot(blockSlotState, slotBeforeBlock);

    return spec.createNewUnsignedBlock(
            executionEngineChannel,
            newSlot,
            spec.getBeaconProposerIndex(blockSlotState, newSlot),
            blockSlotState,
            parentRoot,
            operationSelector.createSelector(
                parentRoot, blockSlotState, randaoReveal, optionalGraffiti))
        .getBlock();
  }

  public void prepareExecutionPayload(
      final Optional<BeaconState> maybeCurrentSlotState, UInt64 targetSlot) {
    operationSelector.prepareExecutionPayload(maybeCurrentSlotState, targetSlot);
  }
}