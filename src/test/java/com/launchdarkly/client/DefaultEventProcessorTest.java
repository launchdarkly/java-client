package com.launchdarkly.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.client.DefaultEventProcessor.EventDispatcher;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.client.TestUtil.hasJsonProperty;
import static com.launchdarkly.client.TestUtil.isJsonArray;
import static com.launchdarkly.client.TestUtil.simpleEvaluation;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class DefaultEventProcessorTest {
  private static final String SDK_KEY = "SDK_KEY";
  private static final LDUser user = new LDUser.Builder("userkey").name("Red").build();
  private static final Gson gson = new Gson();
  private static final JsonElement userJson =
      gson.fromJson("{\"key\":\"userkey\",\"name\":\"Red\"}", JsonElement.class);
  private static final JsonElement filteredUserJson =
      gson.fromJson("{\"key\":\"userkey\",\"privateAttrs\":[\"name\"]}", JsonElement.class);

  private final LDConfig.Builder configBuilder = new LDConfig.Builder().diagnosticOptOut(true);
  private final LDConfig.Builder diagConfigBuilder = new LDConfig.Builder();
  private final MockWebServer server = new MockWebServer();
  private DefaultEventProcessor ep;
  
  @Before
  public void setup() throws Exception {
    server.start();
    configBuilder.eventsURI(server.url("/").uri());
    diagConfigBuilder.eventsURI(server.url("/").uri());
  }
  
  @After
  public void teardown() throws Exception {
    if (ep != null) {
      ep.close();
    }
    server.shutdown();
  }
  
  @Test
  public void identifyEventIsQueued() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(e);

    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(isIdentifyEvent(e, userJson)));
  }
  
  @Test
  public void userIsFilteredInIdentifyEvent() throws Exception {
    configBuilder.allAttributesPrivate(true);
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(e);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(isIdentifyEvent(e, filteredUserJson)));
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void individualFeatureEventIsQueuedWithIndexEvent() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).trackEvents(true).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe, userJson),
        isFeatureEvent(fe, flag, false, null),
        isSummaryEvent()
    ));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void userIsFilteredInIndexEvent() throws Exception {
    configBuilder.allAttributesPrivate(true);
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).trackEvents(true).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe, filteredUserJson),
        isFeatureEvent(fe, flag, false, null),
        isSummaryEvent()
    ));
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void featureEventCanContainInlineUser() throws Exception {
    configBuilder.inlineUsersInEvents(true);
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).trackEvents(true).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isFeatureEvent(fe, flag, false, userJson),
        isSummaryEvent()
    ));
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void userIsFilteredInFeatureEvent() throws Exception {
    configBuilder.inlineUsersInEvents(true).allAttributesPrivate(true);
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).trackEvents(true).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isFeatureEvent(fe, flag, false, filteredUserJson),
        isSummaryEvent()
    ));
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void featureEventCanContainReason() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).trackEvents(true).build();
    EvaluationReason reason = EvaluationReason.ruleMatch(1, null);
    Event.FeatureRequest fe = EventFactory.DEFAULT_WITH_REASONS.newFeatureRequestEvent(flag, user,
        new EvaluationDetail<JsonElement>(reason, 1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe, userJson),
        isFeatureEvent(fe, flag, false, null, reason),
        isSummaryEvent()
    ));
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void indexEventIsStillGeneratedIfInlineUsersIsTrueButFeatureEventIsNotTracked() throws Exception {
    configBuilder.inlineUsersInEvents(true);
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).trackEvents(false).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe, userJson),
        isSummaryEvent()
    ));    
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void eventKindIsDebugIfFlagIsTemporarilyInDebugMode() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    long futureTime = System.currentTimeMillis() + 1000000;
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).debugEventsUntilDate(futureTime).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe, userJson),
        isFeatureEvent(fe, flag, true, userJson),
        isSummaryEvent()
    ));
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void eventCanBeBothTrackedAndDebugged() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    long futureTime = System.currentTimeMillis() + 1000000;
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).trackEvents(true)
        .debugEventsUntilDate(futureTime).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe, userJson),
        isFeatureEvent(fe, flag, false, null),
        isFeatureEvent(fe, flag, true, userJson),
        isSummaryEvent()
    ));
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void debugModeExpiresBasedOnClientTimeIfClientTimeIsLaterThanServerTime() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    
    // Pick a server time that is somewhat behind the client time
    long serverTime = System.currentTimeMillis() - 20000;
    
    // Send and flush an event we don't care about, just to set the last server time
    ep.sendEvent(EventFactory.DEFAULT.newIdentifyEvent(new LDUser.Builder("otherUser").build()));
    flushAndGetEvents(addDateHeader(new MockResponse(), serverTime));
    
    // Now send an event with debug mode on, with a "debug until" time that is further in
    // the future than the server time, but in the past compared to the client.
    long debugUntil = serverTime + 1000;
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).debugEventsUntilDate(debugUntil).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    // Should get a summary event only, not a full feature event
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe, userJson),
        isSummaryEvent(fe.creationDate, fe.creationDate)
    ));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void debugModeExpiresBasedOnServerTimeIfServerTimeIsLaterThanClientTime() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    
    // Pick a server time that is somewhat ahead of the client time
    long serverTime = System.currentTimeMillis() + 20000;
    
    // Send and flush an event we don't care about, just to set the last server time
    ep.sendEvent(EventFactory.DEFAULT.newIdentifyEvent(new LDUser.Builder("otherUser").build()));
    flushAndGetEvents(addDateHeader(new MockResponse(), serverTime));
    
    // Now send an event with debug mode on, with a "debug until" time that is further in
    // the future than the client time, but in the past compared to the server.
    long debugUntil = serverTime - 1000;
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).debugEventsUntilDate(debugUntil).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);
    
    // Should get a summary event only, not a full feature event
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe, userJson),
        isSummaryEvent(fe.creationDate, fe.creationDate)
    ));
  }
  
  @SuppressWarnings("unchecked")
  @Test
  public void twoFeatureEventsForSameUserGenerateOnlyOneIndexEvent() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    FeatureFlag flag1 = new FeatureFlagBuilder("flagkey1").version(11).trackEvents(true).build();
    FeatureFlag flag2 = new FeatureFlagBuilder("flagkey2").version(22).trackEvents(true).build();
    JsonElement value = new JsonPrimitive("value");
    Event.FeatureRequest fe1 = EventFactory.DEFAULT.newFeatureRequestEvent(flag1, user,
        simpleEvaluation(1, value), null);
    Event.FeatureRequest fe2 = EventFactory.DEFAULT.newFeatureRequestEvent(flag2, user,
        simpleEvaluation(1, value), null);
    ep.sendEvent(fe1);
    ep.sendEvent(fe2);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(fe1, userJson),
        isFeatureEvent(fe1, flag1, false, null),
        isFeatureEvent(fe2, flag2, false, null),
        allOf(
            isSummaryEvent(fe1.creationDate, fe2.creationDate),
            hasSummaryFlag(flag1.getKey(), null,
                contains(isSummaryEventCounter(flag1, 1, value, 1))),
            hasSummaryFlag(flag2.getKey(), null,
                contains(isSummaryEventCounter(flag2, 1, value, 1))
            )
        )
    ));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void identifyEventMakesIndexEventUnnecessary() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    Event ie = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(ie);
    FeatureFlag flag = new FeatureFlagBuilder("flagkey").version(11).trackEvents(true).build();
    Event.FeatureRequest fe = EventFactory.DEFAULT.newFeatureRequestEvent(flag, user,
        simpleEvaluation(1, new JsonPrimitive("value")), null);
    ep.sendEvent(fe);

    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, hasItems(
        isIdentifyEvent(ie, userJson),
        isFeatureEvent(fe, flag, false, null),
        isSummaryEvent()
    ));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void nonTrackedEventsAreSummarized() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    FeatureFlag flag1 = new FeatureFlagBuilder("flagkey1").version(11).build();
    FeatureFlag flag2 = new FeatureFlagBuilder("flagkey2").version(22).build();
    JsonElement value = new JsonPrimitive("value");
    JsonElement default1 = new JsonPrimitive("default1");
    JsonElement default2 = new JsonPrimitive("default2");
    Event fe1 = EventFactory.DEFAULT.newFeatureRequestEvent(flag1, user,
        simpleEvaluation(2, value), default1);
    Event fe2 = EventFactory.DEFAULT.newFeatureRequestEvent(flag2, user,
        simpleEvaluation(2, value), default2);
    ep.sendEvent(fe1);
    ep.sendEvent(fe2);
    
    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, hasItems(
        isIndexEvent(fe1, userJson),
        allOf(
            isSummaryEvent(fe1.creationDate, fe2.creationDate),
            hasSummaryFlag(flag1.getKey(), default1,
                contains(isSummaryEventCounter(flag1, 2, value, 1))),
            hasSummaryFlag(flag2.getKey(), default2,
                contains(isSummaryEventCounter(flag2, 2, value, 1)))
        )
    ));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void customEventIsQueuedWithUser() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    JsonObject data = new JsonObject();
    data.addProperty("thing", "stuff");
    Event.Custom ce = EventFactory.DEFAULT.newCustomEvent("eventkey", user, data);
    ep.sendEvent(ce);

    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(
        isIndexEvent(ce, userJson),
        isCustomEvent(ce, null)
    ));
  }

  @Test
  public void customEventCanContainInlineUser() throws Exception {
    configBuilder.inlineUsersInEvents(true);
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    JsonObject data = new JsonObject();
    data.addProperty("thing", "stuff");
    Event.Custom ce = EventFactory.DEFAULT.newCustomEvent("eventkey", user, data);
    ep.sendEvent(ce);

    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(isCustomEvent(ce, userJson)));
  }
  
  @Test
  public void userIsFilteredInCustomEvent() throws Exception {
    configBuilder.inlineUsersInEvents(true).allAttributesPrivate(true);
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    JsonObject data = new JsonObject();
    data.addProperty("thing", "stuff");
    Event.Custom ce = EventFactory.DEFAULT.newCustomEvent("eventkey", user, data);
    ep.sendEvent(ce);

    JsonArray output = flushAndGetEvents(new MockResponse());
    assertThat(output, contains(isCustomEvent(ce, filteredUserJson)));
  }
  
  @Test
  public void closingEventProcessorForcesSynchronousFlush() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(e);

    server.enqueue(new MockResponse());
    ep.close();
    JsonArray output = getEventsFromLastRequest();
    assertThat(output, contains(isIdentifyEvent(e, userJson)));
  }
  
  @Test
  public void nothingIsSentIfThereAreNoEvents() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    ep.close();
    
    assertEquals(0, server.getRequestCount());
  }

  @Test
  public void initialDiagnosticEventSentToDiagnosticEndpoint() throws Exception {
    server.enqueue(new MockResponse());
    ep = new DefaultEventProcessor(SDK_KEY, diagConfigBuilder.build());
    ep.close();
    RecordedRequest req = server.takeRequest(100, TimeUnit.MILLISECONDS);

    assertNotNull(req);
    assertThat(req.getPath(), equalTo("//diagnostic"));
  }

  @Test
  public void initialDiagnosticEventHasInitBody() throws Exception {
    server.enqueue(new MockResponse());
    ep = new DefaultEventProcessor(SDK_KEY, diagConfigBuilder.build());
    ep.close();
    RecordedRequest req = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertNotNull(req);

    DiagnosticEvent.Init initEvent = gson.fromJson(req.getBody().readUtf8(), DiagnosticEvent.Init.class);

    assertNotNull(initEvent);
    assertThat(initEvent.kind, equalTo("diagnostic-init"));
    assertNotNull(initEvent.configuration);
    assertNotNull(initEvent.sdk);
    assertNotNull(initEvent.platform);
    assertNotNull(initEvent.id);
  }

  @Test
  public void periodicDiagnosticEventHasStatisticsBody() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());
    ep = new DefaultEventProcessor(SDK_KEY, diagConfigBuilder.build());
    ep.postDiagnostic();
    ep.close();
    // Ignore the initial diagnostic event
    server.takeRequest(100, TimeUnit.MILLISECONDS);
    RecordedRequest periodReq = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertNotNull(periodReq);

    DiagnosticEvent.Statistics statsEvent = gson.fromJson(periodReq.getBody().readUtf8(), DiagnosticEvent.Statistics.class);

    assertNotNull(statsEvent);
    assertThat(statsEvent.kind, equalTo("diagnostic"));
    assertNotNull(statsEvent.id);
  }

  @Test
  public void sdkKeyIsSent() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(e);
    
    server.enqueue(new MockResponse());
    ep.close();
    RecordedRequest req = server.takeRequest();
    
    assertThat(req.getHeader("Authorization"), equalTo(SDK_KEY));
  }

  @Test
  public void sdkKeyIsSentOnDiagnosticEvents() throws Exception {
    server.enqueue(new MockResponse());
    ep = new DefaultEventProcessor(SDK_KEY, diagConfigBuilder.build());
    ep.close();
    RecordedRequest req = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertNotNull(req);
    assertThat(req.getHeader("Authorization"), equalTo(SDK_KEY));
  }

  @Test
  public void eventSchemaIsSent() throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(e);
    
    server.enqueue(new MockResponse());
    ep.close();
    RecordedRequest req = server.takeRequest();
    
    assertThat(req.getHeader("X-LaunchDarkly-Event-Schema"), equalTo("3"));
  }

  @Test
  public void eventSchemaNotSetOnDiagnosticEvents() throws Exception {
    server.enqueue(new MockResponse());
    ep = new DefaultEventProcessor(SDK_KEY, diagConfigBuilder.build());
    ep.close();
    RecordedRequest req = server.takeRequest(100, TimeUnit.MILLISECONDS);
    assertNotNull(req);
    assertNull(req.getHeader("X-LaunchDarkly-Event-Schema"));
  }

  @Test
  public void wrapperHeaderSentWhenSet() throws Exception {
      LDConfig config = configBuilder
              .wrapperName("Scala")
              .wrapperVersion("0.1.0")
              .build();

      ep = new DefaultEventProcessor(SDK_KEY, config);
      Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
      ep.sendEvent(e);

      server.enqueue(new MockResponse());
      ep.close();
      RecordedRequest req = server.takeRequest();

      assertThat(req.getHeader("X-LaunchDarkly-Wrapper"), equalTo("Scala/0.1.0"));
  }

  @Test
  public void wrapperHeaderSentWithoutVersion() throws Exception {
    LDConfig config = configBuilder
        .wrapperName("Scala")
        .build();

    ep = new DefaultEventProcessor(SDK_KEY, config);
    Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(e);

    server.enqueue(new MockResponse());
    ep.close();
    RecordedRequest req = server.takeRequest();

    assertThat(req.getHeader("X-LaunchDarkly-Wrapper"), equalTo("Scala"));
  }

  @Test
  public void http400ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(400);
  }

  @Test
  public void http401ErrorIsUnrecoverable() throws Exception {
    testUnrecoverableHttpError(401);
  }

  @Test
  public void http403ErrorIsUnrecoverable() throws Exception {
    testUnrecoverableHttpError(403);
  }

  // Cannot test our retry logic for 408, because OkHttp insists on doing its own retry on 408 so that
  // we never actually see that response status.
//  @Test
//  public void http408ErrorIsRecoverable() throws Exception {
//    testRecoverableHttpError(408);
//  }

  @Test
  public void http429ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(429);
  }

  @Test
  public void http500ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(500);
  }
  
  @Test
  public void flushIsRetriedOnceAfter5xxError() throws Exception {
  }
  
  private void testUnrecoverableHttpError(int status) throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(e);
    flushAndGetEvents(new MockResponse().setResponseCode(status));
    
    ep.sendEvent(e);
    ep.flush();
    ep.waitUntilInactive();
    RecordedRequest req = server.takeRequest(0, TimeUnit.SECONDS);
    assertThat(req, nullValue(RecordedRequest.class));
  }
  
  private void testRecoverableHttpError(int status) throws Exception {
    ep = new DefaultEventProcessor(SDK_KEY, configBuilder.build());
    Event e = EventFactory.DEFAULT.newIdentifyEvent(user);
    ep.sendEvent(e);
    
    server.enqueue(new MockResponse().setResponseCode(status));
    server.enqueue(new MockResponse().setResponseCode(status));
    server.enqueue(new MockResponse());
    // need two responses because flush will be retried one time
    
    ep.flush();
    ep.waitUntilInactive();
    RecordedRequest req = server.takeRequest(0, TimeUnit.SECONDS);
    assertThat(req, notNullValue(RecordedRequest.class));
    req = server.takeRequest(0, TimeUnit.SECONDS);
    assertThat(req, notNullValue(RecordedRequest.class));
    req = server.takeRequest(0, TimeUnit.SECONDS);
    assertThat(req, nullValue(RecordedRequest.class)); // only 2 requests total
  }
  
  private MockResponse addDateHeader(MockResponse response, long timestamp) {
    return response.addHeader("Date", EventDispatcher.HTTP_DATE_FORMAT.format(new Date(timestamp)));
  }
  
  private JsonArray flushAndGetEvents(MockResponse response) throws Exception {
    server.enqueue(response);
    ep.flush();
    ep.waitUntilInactive();
    return getEventsFromLastRequest();
  }
  
  private JsonArray getEventsFromLastRequest() throws Exception {
    RecordedRequest req = server.takeRequest(0, TimeUnit.MILLISECONDS);
    assertNotNull(req);
    return gson.fromJson(req.getBody().readUtf8(), JsonElement.class).getAsJsonArray();
  }
  
  private Matcher<JsonElement> isIdentifyEvent(Event sourceEvent, JsonElement user) {
    return allOf(
        hasJsonProperty("kind", "identify"),
        hasJsonProperty("creationDate", (double)sourceEvent.creationDate),
        hasJsonProperty("user", user)
    );
  }

  private Matcher<JsonElement> isIndexEvent(Event sourceEvent, JsonElement user) {
    return allOf(
        hasJsonProperty("kind", "index"),
        hasJsonProperty("creationDate", (double)sourceEvent.creationDate),
        hasJsonProperty("user", user)
    );
  }

  private Matcher<JsonElement> isFeatureEvent(Event.FeatureRequest sourceEvent, FeatureFlag flag, boolean debug, JsonElement inlineUser) {
    return isFeatureEvent(sourceEvent, flag, debug, inlineUser, null);
  }

  @SuppressWarnings("unchecked")
  private Matcher<JsonElement> isFeatureEvent(Event.FeatureRequest sourceEvent, FeatureFlag flag, boolean debug, JsonElement inlineUser,
      EvaluationReason reason) {
    return allOf(
        hasJsonProperty("kind", debug ? "debug" : "feature"),
        hasJsonProperty("creationDate", (double)sourceEvent.creationDate),
        hasJsonProperty("key", flag.getKey()),
        hasJsonProperty("version", (double)flag.getVersion()),
        hasJsonProperty("variation", sourceEvent.variation),
        hasJsonProperty("value", sourceEvent.value),
        (inlineUser != null) ? hasJsonProperty("userKey", nullValue(JsonElement.class)) :
          hasJsonProperty("userKey", sourceEvent.user.getKeyAsString()),
        (inlineUser != null) ? hasJsonProperty("user", inlineUser) :
          hasJsonProperty("user", nullValue(JsonElement.class)),
        (reason == null) ? hasJsonProperty("reason", nullValue(JsonElement.class)) :
          hasJsonProperty("reason", gson.toJsonTree(reason))
    );
  }

  private Matcher<JsonElement> isCustomEvent(Event.Custom sourceEvent, JsonElement inlineUser) {
    return allOf(
        hasJsonProperty("kind", "custom"),
        hasJsonProperty("creationDate", (double)sourceEvent.creationDate),
        hasJsonProperty("key", "eventkey"),
        (inlineUser != null) ? hasJsonProperty("userKey", nullValue(JsonElement.class)) :
          hasJsonProperty("userKey", sourceEvent.user.getKeyAsString()),
        (inlineUser != null) ? hasJsonProperty("user", inlineUser) :
          hasJsonProperty("user", nullValue(JsonElement.class)),
        hasJsonProperty("data", sourceEvent.data)
    );
  }

  private Matcher<JsonElement> isSummaryEvent() {
    return hasJsonProperty("kind", "summary");
  }

  private Matcher<JsonElement> isSummaryEvent(long startDate, long endDate) {
    return allOf(
        hasJsonProperty("kind", "summary"),
        hasJsonProperty("startDate", (double)startDate),
        hasJsonProperty("endDate", (double)endDate)
    );
  }
  
  private Matcher<JsonElement> hasSummaryFlag(String key, JsonElement defaultVal, Matcher<Iterable<? extends JsonElement>> counters) {
    return hasJsonProperty("features",
        hasJsonProperty(key, allOf(
          hasJsonProperty("default", defaultVal),
          hasJsonProperty("counters", isJsonArray(counters))
    )));
  }
  
  private Matcher<JsonElement> isSummaryEventCounter(FeatureFlag flag, Integer variation, JsonElement value, int count) {
    return allOf(
        hasJsonProperty("variation", variation),
        hasJsonProperty("version", (double)flag.getVersion()),
        hasJsonProperty("value", value),
        hasJsonProperty("count", (double)count)
    );
  }
}
