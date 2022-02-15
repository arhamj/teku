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

package tech.pegasys.teku.validator.client.restapi.apis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSTestUtil;
import tech.pegasys.teku.infrastructure.restapi.endpoints.RestApiRequest;
import tech.pegasys.teku.validator.client.ActiveKeyManager;
import tech.pegasys.teku.validator.client.restapi.apis.schema.ExternalValidator;
import tech.pegasys.teku.validator.client.restapi.apis.schema.PostKeyResult;
import tech.pegasys.teku.validator.client.restapi.apis.schema.PostRemoteKeysRequest;

public class PostRemoteKeysTest {
  private final ActiveKeyManager keyManager = mock(ActiveKeyManager.class);
  private final RestApiRequest request = mock(RestApiRequest.class);

  @Test
  void emptyRequest_shouldGiveEmptySuccess() throws JsonProcessingException {
    final PostRemoteKeys endpoint = new PostRemoteKeys(keyManager);
    final PostRemoteKeysRequest body = new PostRemoteKeysRequest();
    when(request.getRequestBody()).thenReturn(body);

    endpoint.handle(request);
    verify(request).respondOk(List.of());
  }

  @Test
  void validResponse_shouldGiveValidPostKeyResults()
      throws JsonProcessingException, MalformedURLException {
    final PostRemoteKeys endpoint = new PostRemoteKeys(keyManager);

    List<ExternalValidator> externalValidators =
        List.of(
            new ExternalValidator(
                BLSTestUtil.randomKeyPair(1).getPublicKey(),
                Optional.of(new URL("http://host.com"))),
            new ExternalValidator(BLSTestUtil.randomKeyPair(2).getPublicKey(), Optional.empty()));
    final PostRemoteKeysRequest body = new PostRemoteKeysRequest(externalValidators);
    when(request.getRequestBody()).thenReturn(body);

    List<PostKeyResult> results = List.of(PostKeyResult.success(), PostKeyResult.success());
    when(keyManager.importExternalValidators(externalValidators)).thenReturn(results);

    endpoint.handle(request);
    verify(request).respondOk(results);
  }

  @Test
  void duplicate_shouldGiveDuplicateResponse()
      throws JsonProcessingException, MalformedURLException {
    final PostRemoteKeys endpoint = new PostRemoteKeys(keyManager);

    BLSPublicKey publicKey = BLSTestUtil.randomKeyPair(1).getPublicKey();
    URL url = new URL("http://host.com");

    List<ExternalValidator> externalValidators =
        List.of(
            new ExternalValidator(publicKey, Optional.of(url)),
            new ExternalValidator(publicKey, Optional.of(url)));
    final PostRemoteKeysRequest body = new PostRemoteKeysRequest(externalValidators);
    when(request.getRequestBody()).thenReturn(body);

    List<PostKeyResult> results = List.of(PostKeyResult.success(), PostKeyResult.duplicate());
    when(keyManager.importExternalValidators(externalValidators)).thenReturn(results);

    endpoint.handle(request);
    verify(request).respondOk(results);
  }
}
