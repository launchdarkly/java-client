package com.launchdarkly.sdk.server;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonElement;
import com.launchdarkly.eventsource.ConnectionErrorHandler;
import com.launchdarkly.eventsource.ConnectionErrorHandler.Action;
import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;
import com.launchdarkly.sdk.server.DataModel.VersionedData;
import com.launchdarkly.sdk.server.interfaces.DataSource;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.interfaces.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.interfaces.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.interfaces.DataStoreUpdates;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static com.launchdarkly.sdk.server.Util.configureHttpClientBuilder;
import static com.launchdarkly.sdk.server.Util.getHeadersBuilderFor;
import static com.launchdarkly.sdk.server.Util.httpErrorMessage;
import static com.launchdarkly.sdk.server.Util.isHttpErrorRecoverable;

import okhttp3.Headers;
import okhttp3.OkHttpClient;

/**
 * Implementation of the streaming data source, not including the lower-level SSE implementation which is in
 * okhttp-eventsource.
 * 
 * Error handling works as follows:
 * 1. If any event is malformed, we must assume the stream is broken and we may have missed updates. Restart it.
 * 2. If we try to put updates into the data store and we get an error, we must assume something's wrong with the
 * data store.
 * 2a. If the data store supports status notifications (which all persistent stores normally do), then we can
 * assume it has entered a failed state and will notify us once it is working again. If and when it recovers, then
 * it will tell us whether we need to restart the stream (to ensure that we haven't missed any updates), or
 * whether it has already persisted all of the stream updates we received during the outage.
 * 2b. If the data store doesn't support status notifications (which is normally only true of the in-memory store)
 * then we don't know the significance of the error, but we must assume that updates have been lost, so we'll
 * restart the stream.
 * 3. If we receive an unrecoverable error like HTTP 401, we close the stream and don't retry. Any other HTTP
 * error or network error causes a retry with backoff.
 * 4. We close the closeWhenReady channel to tell the client initialization logic that initialization has either
 * succeeded (we got an initial payload and successfully stored it) or permanently failed (we got a 401, etc.).
 * Otherwise, the client initialization method may time out but we will still be retrying in the background, and
 * if we succeed then the client can detect that we're initialized now by calling our Initialized method.
 */
final class StreamProcessor implements DataSource {
  private static final String PUT = "put";
  private static final String PATCH = "patch";
  private static final String DELETE = "delete";
  private static final String INDIRECT_PUT = "indirect/put";
  private static final String INDIRECT_PATCH = "indirect/patch";
  private static final Logger logger = LoggerFactory.getLogger(StreamProcessor.class);
  private static final Duration DEAD_CONNECTION_INTERVAL = Duration.ofSeconds(300);

  private final DataStoreUpdates dataStoreUpdates;
  private final HttpConfiguration httpConfig;
  private final Headers headers;
  @VisibleForTesting final URI streamUri;
  @VisibleForTesting final Duration initialReconnectDelay;
  @VisibleForTesting final FeatureRequestor requestor;
  private final DiagnosticAccumulator diagnosticAccumulator;
  private final EventSourceCreator eventSourceCreator;
  private final DataStoreStatusProvider.StatusListener statusListener;
  private volatile EventSource es;
  private final AtomicBoolean initialized = new AtomicBoolean(false);
  private volatile long esStarted = 0;
  private volatile boolean lastStoreUpdateFailed = false;

  ConnectionErrorHandler connectionErrorHandler = createDefaultConnectionErrorHandler(); // exposed for testing
  
  static final class EventSourceParams {
    final EventHandler handler;
    final URI streamUri;
    final Duration initialReconnectDelay;
    final ConnectionErrorHandler errorHandler;
    final Headers headers;
    final HttpConfiguration httpConfig;
    
    EventSourceParams(EventHandler handler, URI streamUri, Duration initialReconnectDelay,
        ConnectionErrorHandler errorHandler, Headers headers, HttpConfiguration httpConfig) {
      this.handler = handler;
      this.streamUri = streamUri;
      this.initialReconnectDelay = initialReconnectDelay;
      this.errorHandler = errorHandler;
      this.headers = headers;
      this.httpConfig = httpConfig;
    }
  }
  
  @FunctionalInterface
  static interface EventSourceCreator {
    EventSource createEventSource(EventSourceParams params);
  }
  
  StreamProcessor(
      String sdkKey,
      HttpConfiguration httpConfig,
      FeatureRequestor requestor,
      DataStoreUpdates dataStoreUpdates,
      EventSourceCreator eventSourceCreator,
      DiagnosticAccumulator diagnosticAccumulator,
      URI streamUri,
      Duration initialReconnectDelay
      ) {
    this.dataStoreUpdates = dataStoreUpdates;
    this.httpConfig = httpConfig;
    this.requestor = requestor;
    this.diagnosticAccumulator = diagnosticAccumulator;
    this.eventSourceCreator = eventSourceCreator != null ? eventSourceCreator : StreamProcessor::defaultEventSourceCreator;
    this.streamUri = streamUri;
    this.initialReconnectDelay = initialReconnectDelay;

    this.headers = getHeadersBuilderFor(sdkKey, httpConfig)
        .add("Accept", "text/event-stream")
        .build();
    
    DataStoreStatusProvider.StatusListener statusListener = this::onStoreStatusChanged;
    if (dataStoreUpdates.getStatusProvider().addStatusListener(statusListener)) {
      this.statusListener = statusListener;
    } else {
      this.statusListener = null;
    }
  }

  private void onStoreStatusChanged(DataStoreStatusProvider.Status newStatus) {
    if (newStatus.isAvailable()) {
      if (newStatus.isRefreshNeeded()) {
        // The store has just transitioned from unavailable to available, and we can't guarantee that
        // all of the latest data got cached, so let's restart the stream to refresh all the data.
        EventSource stream = es;
        if (stream != null) {
          logger.warn("Restarting stream to refresh data after data store outage");
          stream.restart();
        }
      }
    }
  }
  
  private ConnectionErrorHandler createDefaultConnectionErrorHandler() {
    return (Throwable t) -> {
      recordStreamInit(true);
      if (t instanceof UnsuccessfulResponseException) {
        int status = ((UnsuccessfulResponseException)t).getCode();
        logger.error(httpErrorMessage(status, "streaming connection", "will retry"));
        if (!isHttpErrorRecoverable(status)) {
          return Action.SHUTDOWN;
        }
        esStarted = System.currentTimeMillis();
        return Action.PROCEED;
      }
      return Action.PROCEED;
    };
  }
  
  @Override
  public Future<Void> start() {
    final SettableFuture<Void> initFuture = SettableFuture.create();

    ConnectionErrorHandler wrappedConnectionErrorHandler = (Throwable t) -> {
      Action result = connectionErrorHandler.onConnectionError(t);
      if (result == Action.SHUTDOWN) {
        initFuture.set(null); // if client is initializing, make it stop waiting; has no effect if already inited
      }
      return result;
    };

    EventHandler handler = new StreamEventHandler(initFuture);
    
    es = eventSourceCreator.createEventSource(new EventSourceParams(handler,
        URI.create(streamUri.toASCIIString() + "/all"),
        initialReconnectDelay,
        wrappedConnectionErrorHandler,
        headers,
        httpConfig));
    esStarted = System.currentTimeMillis();
    es.start();
    return initFuture;
  }

  private void recordStreamInit(boolean failed) {
    if (diagnosticAccumulator != null && esStarted != 0) {
      diagnosticAccumulator.recordStreamInit(esStarted, System.currentTimeMillis() - esStarted, failed);
    }
  }

  @Override
  public void close() throws IOException {
    logger.info("Closing LaunchDarkly StreamProcessor");
    if (statusListener != null) {
      dataStoreUpdates.getStatusProvider().removeStatusListener(statusListener);
    }
    if (es != null) {
      es.close();
    }
    requestor.close();
  }

  @Override
  public boolean isInitialized() {
    return initialized.get();
  }

  private class StreamEventHandler implements EventHandler {
    private final SettableFuture<Void> initFuture;
    
    StreamEventHandler(SettableFuture<Void> initFuture) {
      this.initFuture = initFuture;
    }
    
    @Override
    public void onOpen() throws Exception {
    }

    @Override
    public void onClosed() throws Exception {
    }

    @Override
    public void onMessage(String name, MessageEvent event) throws Exception {
      try {
        switch (name) {
          case PUT:
            handlePut(event.getData());
            break;
         
          case PATCH:
            handlePatch(event.getData());
            break;
            
          case DELETE:
            handleDelete(event.getData()); 
            break;

          case INDIRECT_PUT:
            handleIndirectPut();
            break;
            
          case INDIRECT_PATCH:
            handleIndirectPatch(event.getData());
            break;
            
          default:
            logger.warn("Unexpected event found in stream: " + name);
            break;
        }
        lastStoreUpdateFailed = false;
      } catch (StreamStoreException e) {
        // See item 2 in error handling comments at top of class
        if (!lastStoreUpdateFailed) {
          logger.error("Unexpected data store failure when storing updates from stream: {}",
              e.getCause().toString());
          logger.debug(e.getCause().toString(), e.getCause());
        }
        if (statusListener == null) {
          if (!lastStoreUpdateFailed) {
            logger.warn("Restarting stream to ensure that we have the latest data");
          }
          es.restart();
        }
        lastStoreUpdateFailed = true;
      } catch (Exception e) {
        logger.warn("Unexpected error from stream processor: {}", e.toString());
        logger.debug(e.toString(), e);
      }
    }

    private void handlePut(String eventData) throws StreamStoreException {
      recordStreamInit(false);
      esStarted = 0;
      PutData putData = parseStreamJson(PutData.class, eventData);
      FullDataSet<ItemDescriptor> allData = putData.data.toFullDataSet();
      try {
        dataStoreUpdates.init(allData);
      } catch (Exception e) {
        throw new StreamStoreException(e);
      }
      if (!initialized.getAndSet(true)) {
        initFuture.set(null);
        logger.info("Initialized LaunchDarkly client.");
      }
    }

    private void handlePatch(String eventData) throws StreamStoreException {
      PatchData data = parseStreamJson(PatchData.class, eventData);
      Map.Entry<DataKind, String> kindAndKey = getKindAndKeyFromStreamApiPath(data.path);
      DataKind kind = kindAndKey.getKey();
      String key = kindAndKey.getValue();
      ItemDescriptor item = deserializeFromParsedJson(kind, data.data);
      try {
        dataStoreUpdates.upsert(kind, key, item);
      } catch (Exception e) {
        throw new StreamStoreException(e);
      }
    }

    private void handleDelete(String eventData) throws StreamStoreException {
      DeleteData data = parseStreamJson(DeleteData.class, eventData);
      Map.Entry<DataKind, String> kindAndKey = getKindAndKeyFromStreamApiPath(data.path);
      DataKind kind = kindAndKey.getKey();
      String key = kindAndKey.getValue();
      ItemDescriptor placeholder = new ItemDescriptor(data.version, null);
      try {
        dataStoreUpdates.upsert(kind, key, placeholder);
      } catch (Exception e) {
        throw new StreamStoreException(e);
      }
    }

    private void handleIndirectPut() throws StreamStoreException, HttpErrorException, IOException {
      FeatureRequestor.AllData putData = requestor.getAllData();
      FullDataSet<ItemDescriptor> allData = putData.toFullDataSet();
      try {
        dataStoreUpdates.init(allData);
      } catch (Exception e) {
        throw new StreamStoreException(e);
      }
      if (!initialized.getAndSet(true)) {
        initFuture.set(null);
        logger.info("Initialized LaunchDarkly client.");
      }
    }

    private void handleIndirectPatch(String path) throws StreamStoreException, HttpErrorException, IOException {
      Map.Entry<DataKind, String> kindAndKey = getKindAndKeyFromStreamApiPath(path);
      DataKind kind = kindAndKey.getKey();
      String key = kindAndKey.getValue();
      VersionedData item = kind == SEGMENTS ? requestor.getSegment(key) : requestor.getFlag(key);
      try {
        dataStoreUpdates.upsert(kind, key, new ItemDescriptor(item.getVersion(), item));
      } catch (Exception e) {
        throw new StreamStoreException(e);
      }
    }

    @Override
    public void onComment(String comment) {
      logger.debug("Received a heartbeat");
    }

    @Override
    public void onError(Throwable throwable) {
      logger.warn("Encountered EventSource error: {}", throwable.toString());
      logger.debug(throwable.toString(), throwable);
    }  
  }

  private static EventSource defaultEventSourceCreator(EventSourceParams params) {
    EventSource.Builder builder = new EventSource.Builder(params.handler, params.streamUri)
        .clientBuilderActions(new EventSource.Builder.ClientConfigurer() {
          public void configure(OkHttpClient.Builder builder) {
            configureHttpClientBuilder(params.httpConfig, builder);
          }
        })
        .connectionErrorHandler(params.errorHandler)
        .headers(params.headers)
        .reconnectTime(params.initialReconnectDelay)
        .readTimeout(DEAD_CONNECTION_INTERVAL);
    // Note that this is not the same read timeout that can be set in LDConfig.  We default to a smaller one
    // there because we don't expect long delays within any *non*-streaming response that the LD client gets.
    // A read timeout on the stream will result in the connection being cycled, so we set this to be slightly
    // more than the expected interval between heartbeat signals.

    return builder.build();
  }
  
  private static Map.Entry<DataKind, String> getKindAndKeyFromStreamApiPath(String path) {
    for (DataKind kind: DataModel.ALL_DATA_KINDS) {
      String prefix = (kind == SEGMENTS) ? "/segments/" : "/flags/";
      if (path.startsWith(prefix)) {
        return new AbstractMap.SimpleEntry<>(kind, path.substring(prefix.length()));
      }
    }
    throw new RuntimeException(new IllegalArgumentException("unrecognized item path: " + path));
  }

  // This helper method is currently trivial but will be used for better error handling in the future, and helps to
  // minimize usage of Gson-specific APIs throughout the code.
  private static <T> T parseStreamJson(Class<T> c, String json) {
    return JsonHelpers.gsonInstance().fromJson(json, c);
  }
  
  private static ItemDescriptor deserializeFromParsedJson(DataKind kind, JsonElement parsedJson) {
    VersionedData item;
    if (kind == FEATURES) {
      item = JsonHelpers.gsonInstance().fromJson(parsedJson, DataModel.FeatureFlag.class);
    } else if (kind == SEGMENTS) {
      item = JsonHelpers.gsonInstance().fromJson(parsedJson, DataModel.Segment.class);
    } else { // this case should never happen
      return kind.deserialize(JsonHelpers.gsonInstance().toJson(parsedJson));
    }
    return new ItemDescriptor(item.getVersion(), item);
  }

  // This exception class indicates that the data store failed to persist an update.
  @SuppressWarnings("serial")
  private static final class StreamStoreException extends Exception {
    public StreamStoreException(Throwable cause) {
      super(cause);
    }
  }
  
  private static final class PutData {
    FeatureRequestor.AllData data;
    
    @SuppressWarnings("unused") // used by Gson
    public PutData() { }
  }
  
  private static final class PatchData {
    String path;
    JsonElement data;

    @SuppressWarnings("unused") // used by Gson
    public PatchData() { }
  }

  private static final class DeleteData {
    String path;
    int version;

    @SuppressWarnings("unused") // used by Gson
    public DeleteData() { }
  }
}
