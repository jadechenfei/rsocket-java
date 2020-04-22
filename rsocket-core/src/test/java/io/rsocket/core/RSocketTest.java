/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.core;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.exceptions.ApplicationErrorException;
import io.rsocket.exceptions.CustomRSocketException;
import io.rsocket.lease.RequesterLeaseHandler;
import io.rsocket.lease.ResponderLeaseHandler;
import io.rsocket.test.util.LocalDuplexConnection;
import io.rsocket.test.util.TestSubscriber;
import io.rsocket.util.DefaultPayload;
import io.rsocket.util.EmptyPayload;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.hamcrest.MatcherAssert;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

public class RSocketTest {

  @Rule public final SocketRule rule = new SocketRule();

  public static void assertError(String s, String mode, ArrayList<Throwable> errors) {
    for (Throwable t : errors) {
      if (t.toString().equals(s)) {
        return;
      }
    }

    Assert.fail("Expected " + mode + " connection error: " + s + " other errors " + errors.size());
  }

  @Test(timeout = 2_000)
  public void testRequestReplyNoError() {
    StepVerifier.create(rule.crs.requestResponse(DefaultPayload.create("hello")))
        .expectNextCount(1)
        .expectComplete()
        .verify();
  }

  @Test(timeout = 2000)
  public void testHandlerEmitsError() {
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Mono<Payload> requestResponse(Payload payload) {
            return Mono.error(new NullPointerException("Deliberate exception."));
          }
        });
    Subscriber<Payload> subscriber = TestSubscriber.create();
    rule.crs.requestResponse(EmptyPayload.INSTANCE).subscribe(subscriber);
    verify(subscriber).onError(any(ApplicationErrorException.class));

    // Client sees error through normal API
    rule.assertNoClientErrors();

    rule.assertServerError("java.lang.NullPointerException: Deliberate exception.");
  }

  @Test(timeout = 2000)
  public void testHandlerEmitsCustomError() {
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Mono<Payload> requestResponse(Payload payload) {
            return Mono.error(
                new CustomRSocketException(0x00000501, "Deliberate Custom exception."));
          }
        });
    Subscriber<Payload> subscriber = TestSubscriber.create();
    rule.crs.requestResponse(EmptyPayload.INSTANCE).subscribe(subscriber);
    ArgumentCaptor<CustomRSocketException> customRSocketExceptionArgumentCaptor =
        ArgumentCaptor.forClass(CustomRSocketException.class);
    verify(subscriber).onError(customRSocketExceptionArgumentCaptor.capture());

    Assert.assertEquals(
        "Deliberate Custom exception.",
        customRSocketExceptionArgumentCaptor.getValue().getMessage());
    Assert.assertEquals(0x00000501, customRSocketExceptionArgumentCaptor.getValue().errorCode());

    // Client sees error through normal API
    rule.assertNoClientErrors();

    rule.assertServerError("CustomRSocketException (0x501): Deliberate Custom exception.");
  }

  @Test(timeout = 2000)
  public void testRequestPropagatesCorrectlyForRequestChannel() {
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
            return Flux.from(payloads)
                // specifically limits request to 3 in order to prevent 256 request from limitRate
                // hidden on the responder side
                .limitRequest(3);
          }
        });

    Flux.range(0, 3)
        .map(i -> DefaultPayload.create("" + i))
        .as(rule.crs::requestChannel)
        .as(publisher -> StepVerifier.create(publisher, 3))
        .expectSubscription()
        .expectNextCount(3)
        .expectComplete()
        .verify(Duration.ofMillis(5000));

    rule.assertNoClientErrors();
    rule.assertNoServerErrors();
  }

  @Test(timeout = 2000)
  public void testStream() throws Exception {
    Flux<Payload> responses = rule.crs.requestStream(DefaultPayload.create("Payload In"));
    StepVerifier.create(responses).expectNextCount(10).expectComplete().verify();
  }

  @Test(timeout = 2000)
  public void testChannel() throws Exception {
    Flux<Payload> requests =
        Flux.range(0, 10).map(i -> DefaultPayload.create("streaming in -> " + i));
    Flux<Payload> responses = rule.crs.requestChannel(requests);
    StepVerifier.create(responses).expectNextCount(10).expectComplete().verify();
  }

  @Test(timeout = 2000)
  public void testErrorPropagatesCorrectly() {
    AtomicReference<Throwable> error = new AtomicReference<>();
    rule.setRequestAcceptor(
        new AbstractRSocket() {
          @Override
          public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
            return Flux.from(payloads).doOnError(error::set);
          }
        });
    Flux<Payload> requests = Flux.error(new RuntimeException("test"));
    Flux<Payload> responses = rule.crs.requestChannel(requests);
    StepVerifier.create(responses).expectErrorMessage("test").verify();
    Assertions.assertThat(error.get()).isNull();
  }

  public static class SocketRule extends ExternalResource {

    DirectProcessor<ByteBuf> serverProcessor;
    DirectProcessor<ByteBuf> clientProcessor;
    private RSocketRequester crs;

    @SuppressWarnings("unused")
    private RSocketResponder srs;

    private RSocket requestAcceptor;
    private ArrayList<Throwable> clientErrors = new ArrayList<>();
    private ArrayList<Throwable> serverErrors = new ArrayList<>();

    @Override
    public Statement apply(Statement base, Description description) {
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          init();
          base.evaluate();
        }
      };
    }

    protected void init() {
      serverProcessor = DirectProcessor.create();
      clientProcessor = DirectProcessor.create();

      LocalDuplexConnection serverConnection =
          new LocalDuplexConnection("server", clientProcessor, serverProcessor);
      LocalDuplexConnection clientConnection =
          new LocalDuplexConnection("client", serverProcessor, clientProcessor);

      requestAcceptor =
          null != requestAcceptor
              ? requestAcceptor
              : new AbstractRSocket() {
                @Override
                public Mono<Payload> requestResponse(Payload payload) {
                  return Mono.just(payload);
                }

                @Override
                public Flux<Payload> requestStream(Payload payload) {
                  return Flux.range(1, 10)
                      .map(
                          i -> DefaultPayload.create("server got -> [" + payload.toString() + "]"));
                }

                @Override
                public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
                  Flux.from(payloads)
                      .map(
                          payload ->
                              DefaultPayload.create("server got -> [" + payload.toString() + "]"))
                      .subscribe();

                  return Flux.range(1, 10)
                      .map(
                          payload ->
                              DefaultPayload.create("server got -> [" + payload.toString() + "]"));
                }
              };

      srs =
          new RSocketResponder(
              ByteBufAllocator.DEFAULT,
              serverConnection,
              requestAcceptor,
              DefaultPayload::create,
              throwable -> serverErrors.add(throwable),
              ResponderLeaseHandler.None,
              0);

      crs =
          new RSocketRequester(
              ByteBufAllocator.DEFAULT,
              clientConnection,
              DefaultPayload::create,
              throwable -> clientErrors.add(throwable),
              StreamIdSupplier.clientSupplier(),
              0,
              0,
              0,
              null,
              RequesterLeaseHandler.None);
    }

    public void setRequestAcceptor(RSocket requestAcceptor) {
      this.requestAcceptor = requestAcceptor;
      init();
    }

    public void assertNoErrors() {
      assertNoClientErrors();
      assertNoServerErrors();
    }

    public void assertNoClientErrors() {
      MatcherAssert.assertThat(
          "Unexpected error on the client connection.", clientErrors, is(empty()));
    }

    public void assertNoServerErrors() {
      MatcherAssert.assertThat(
          "Unexpected error on the server connection.", serverErrors, is(empty()));
    }

    public void assertClientError(String s) {
      assertError(s, "client", this.clientErrors);
    }

    public void assertServerError(String s) {
      assertError(s, "server", this.serverErrors);
    }
  }
}
