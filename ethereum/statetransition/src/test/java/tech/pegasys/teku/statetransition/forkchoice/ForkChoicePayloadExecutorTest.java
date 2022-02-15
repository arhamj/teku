/*
 * Copyright 2021 ConsenSys AG.
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

package tech.pegasys.teku.statetransition.forkchoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.spec.executionengine.PayloadStatus.VALID;

import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayload;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadHeader;
import tech.pegasys.teku.spec.executionengine.ExecutionEngineChannel;
import tech.pegasys.teku.spec.executionengine.PayloadStatus;
import tech.pegasys.teku.spec.logic.common.block.AbstractBlockProcessor;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsBellatrix;
import tech.pegasys.teku.spec.util.DataStructureUtil;

class ForkChoicePayloadExecutorTest {

  private final Spec spec = TestSpecFactory.createMinimalBellatrix();
  private final SchemaDefinitionsBellatrix schemaDefinitionsBellatrix =
      spec.getGenesisSchemaDefinitions().toVersionBellatrix().orElseThrow();
  private final ExecutionPayload defaultPayload =
      schemaDefinitionsBellatrix.getExecutionPayloadSchema().getDefault();
  private final ExecutionPayloadHeader defaultPayloadHeader =
      schemaDefinitionsBellatrix.getExecutionPayloadHeaderSchema().getDefault();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final SafeFuture<PayloadStatus> executionResult = new SafeFuture<>();
  private final ExecutionEngineChannel executionEngine = mock(ExecutionEngineChannel.class);
  private final MergeTransitionBlockValidator transitionValidator =
      mock(MergeTransitionBlockValidator.class);
  private final ExecutionPayloadHeader payloadHeader =
      dataStructureUtil.randomExecutionPayloadHeader();
  private final ExecutionPayload payload = dataStructureUtil.randomExecutionPayload();
  private final SignedBeaconBlock block = dataStructureUtil.randomSignedBeaconBlock(0);

  @BeforeAll
  public static void initSession() {
    AbstractBlockProcessor.BLS_VERIFY_DEPOSIT = false;
  }

  @AfterAll
  public static void resetSession() {
    AbstractBlockProcessor.BLS_VERIFY_DEPOSIT = true;
  }

  @BeforeEach
  void setUp() {
    when(executionEngine.newPayload(any())).thenReturn(executionResult);
  }

  @Test
  void optimisticallyExecute_shouldSendToExecutionEngineAndReturnTrue() {
    final ForkChoicePayloadExecutor payloadExecutor = createPayloadExecutor();
    final boolean result = payloadExecutor.optimisticallyExecute(payloadHeader, payload);
    verify(executionEngine).newPayload(payload);
    assertThat(result).isTrue();
  }

  @Test
  void optimisticallyExecute_shouldNotExecuteDefaultPayload() {
    final ForkChoicePayloadExecutor payloadExecutor = createPayloadExecutor();
    final boolean result = payloadExecutor.optimisticallyExecute(payloadHeader, defaultPayload);
    verify(executionEngine, never()).newPayload(any());
    assertThat(result).isTrue();
    assertThat(payloadExecutor.getExecutionResult()).isCompletedWithValue(PayloadStatus.VALID);
  }

  @Test
  void optimisticallyExecute_shouldValidateMergeBlockWhenThisIsTheMergeBlock() {
    when(executionEngine.newPayload(payload)).thenReturn(SafeFuture.completedFuture(VALID));
    when(executionEngine.getPowBlock(payload.getParentHash())).thenReturn(new SafeFuture<>());
    final ForkChoicePayloadExecutor payloadExecutor = createPayloadExecutor();
    final boolean result = payloadExecutor.optimisticallyExecute(defaultPayloadHeader, payload);

    // Should execute first and then begin validation of the transition block conditions.
    verify(executionEngine).newPayload(payload);
    verify(transitionValidator).verifyTransitionBlock(defaultPayloadHeader, block);
    assertThat(result).isTrue();
  }

  @Test
  void optimisticallyExecute_shouldReturnFailedExecutionOnMergeBlockWhenELOfflineAtExecution() {
    when(executionEngine.newPayload(payload)).thenReturn(SafeFuture.failedFuture(new Error()));
    final ForkChoicePayloadExecutor payloadExecutor = createPayloadExecutor();
    final boolean execution = payloadExecutor.optimisticallyExecute(defaultPayloadHeader, payload);

    // Should not attempt to validate transition conditions because execute payload failed
    verify(transitionValidator, never()).verifyTransitionBlock(defaultPayloadHeader, block);
    verify(executionEngine).newPayload(payload);
    assertThat(execution).isTrue();
    assertThat(payloadExecutor.getExecutionResult())
        .isCompletedWithValueMatching(PayloadStatus::hasFailedExecution);
  }

  @Test
  void
      optimisticallyExecute_shouldReturnFailedExecutionOnMergeBlockWhenELGoesOfflineAfterExecution() {
    when(executionEngine.newPayload(payload)).thenReturn(SafeFuture.completedFuture(VALID));
    when(transitionValidator.verifyTransitionBlock(defaultPayloadHeader, block))
        .thenReturn(SafeFuture.failedFuture(new Error()));
    final ForkChoicePayloadExecutor payloadExecutor = createPayloadExecutor();
    final boolean execution = payloadExecutor.optimisticallyExecute(defaultPayloadHeader, payload);

    verify(transitionValidator).verifyTransitionBlock(defaultPayloadHeader, block);
    verify(executionEngine).newPayload(payload);
    assertThat(execution).isTrue();
    assertThat(payloadExecutor.getExecutionResult())
        .isCompletedWithValueMatching(PayloadStatus::hasFailedExecution);
  }

  @Test
  void optimisticallyExecute_shouldNotVerifyTransitionIfExecutePayloadIsInvalid() {
    final PayloadStatus expectedResult =
        PayloadStatus.invalid(Optional.empty(), Optional.of("Nope"));
    when(executionEngine.newPayload(payload))
        .thenReturn(SafeFuture.completedFuture(expectedResult));
    final ForkChoicePayloadExecutor payloadExecutor = createPayloadExecutor();
    final boolean execution = payloadExecutor.optimisticallyExecute(defaultPayloadHeader, payload);

    verify(executionEngine).newPayload(payload);
    verify(transitionValidator, never()).verifyTransitionBlock(defaultPayloadHeader, block);
    assertThat(execution).isTrue();
    assertThat(payloadExecutor.getExecutionResult()).isCompletedWithValue(expectedResult);
  }

  @Test
  void shouldReturnValidImmediatelyWhenNoPayloadExecuted() {
    final ForkChoicePayloadExecutor payloadExecutor = createPayloadExecutor();

    final SafeFuture<PayloadStatus> result = payloadExecutor.getExecutionResult();
    assertThat(result).isCompletedWithValue(VALID);
  }

  @Test
  void shouldReturnExecutionResultWhenExecuted() {
    when(transitionValidator.verifyTransitionBlock(payloadHeader, block))
        .thenReturn(SafeFuture.completedFuture(VALID));
    final ForkChoicePayloadExecutor payloadExecutor = createPayloadExecutor();
    payloadExecutor.optimisticallyExecute(payloadHeader, payload);

    final SafeFuture<PayloadStatus> result = payloadExecutor.getExecutionResult();
    assertThat(result).isNotCompleted();

    this.executionResult.complete(VALID);

    assertThat(result).isCompletedWithValue(VALID);
  }

  private ForkChoicePayloadExecutor createPayloadExecutor() {
    return new ForkChoicePayloadExecutor(block, executionEngine, transitionValidator);
  }
}
