package com.launchdarkly.client;


import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonElement;
import org.apache.http.annotation.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * A client for the LaunchDarkly API. Client instances are thread-safe. Applications should instantiate
 * a single {@code LDClient} for the lifetime of their application.
 */
@ThreadSafe
public class LDClient implements Closeable {
  private static final Logger logger = LoggerFactory.getLogger(LDClient.class);
  private final LDConfig config;
  private final FeatureRequestor requestor;
  private final EventProcessor eventProcessor;
  private UpdateProcessor updateProcessor;
  protected static final String CLIENT_VERSION = getClientVersion();
  private volatile boolean offline = false;


  /**
   * Creates a new client instance that connects to LaunchDarkly with the default configuration. In most
   * cases, you should use this constructor.
   *
   * @param apiKey        the API key for your account
   * @param waitForMillis when set to greater than zero allows callers to block until the client
   *                      has connected to LaunchDarkly and is properly initialized
   */
  public LDClient(String apiKey, Long waitForMillis) {
    this(apiKey, LDConfig.DEFAULT, waitForMillis);
  }

  /**
   * Creates a new client to connect to LaunchDarkly with a custom configuration. This constructor
   * can be used to configure advanced client features, such as customizing the LaunchDarkly base URL.
   *
   * @param apiKey        the API key for your account
   * @param config        a client configuration object
   * @param waitForMillis when set to greater than zero allows callers to block until the client
   *                      has connected to LaunchDarkly and is properly initialized
   */
  public LDClient(String apiKey, LDConfig config, Long waitForMillis) {
    this.config = config;
    this.requestor = createFeatureRequestor(apiKey, config);
    this.eventProcessor = createEventProcessor(apiKey, config);

    if (config.offline || config.useLdd) {
      logger.info("Starting LaunchDarkly client in offline mode");
      setOffline();
      return;
    }

    if (config.stream) {
      logger.info("Enabling streaming API");
      this.updateProcessor = createStreamProcessor(apiKey, config, requestor);
    } else {
      logger.info("Disabling streaming API");
      this.updateProcessor = createPollingProcessor(config);
    }

    Future<Void> startFuture = updateProcessor.start();

    if (waitForMillis > 0L) {
      logger.info("Waiting up to " + waitForMillis + " milliseconds for LaunchDarkly client to start...");
      try {
        startFuture.get(waitForMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        logger.error("Timeout encountered waiting for LaunchDarkly client initialization");
      } catch (Exception e) {
        logger.error("Exception encountered waiting for LaunchDarkly client initialization", e);
      }
    }
  }

  public boolean initialized() {
    return isOffline() || config.useLdd || updateProcessor.initialized();
  }

  @VisibleForTesting
  protected FeatureRequestor createFeatureRequestor(String apiKey, LDConfig config) {
    return new FeatureRequestor(apiKey, config);
  }

  @VisibleForTesting
  protected EventProcessor createEventProcessor(String apiKey, LDConfig config) {
    return new EventProcessor(apiKey, config);
  }

  @VisibleForTesting
  protected StreamProcessor createStreamProcessor(String apiKey, LDConfig config, FeatureRequestor requestor) {
    return new StreamProcessor(apiKey, config, requestor);
  }

  @VisibleForTesting
  protected PollingProcessor createPollingProcessor(LDConfig config) {
    return new PollingProcessor(config, requestor);
  }


  /**
   * Tracks that a user performed an event.
   *
   * @param eventName the name of the event
   * @param user the user that performed the event
   * @param data a JSON object containing additional data associated with the event
   */
  public void track(String eventName, LDUser user, JsonElement data) {
    boolean processed = eventProcessor.sendEvent(new CustomEvent(eventName, user, data));
    if (!processed) {
      logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
    }
  }

  /**
   * Tracks that a user performed an event.
   *
   * @param eventName the name of the event
   * @param user the user that performed the event
   */
  public void track(String eventName, LDUser user) {
    if (this.offline) {
      return;
    }
    track(eventName, user, null);
  }

  /**
   * Register the user
   * @param user the user to register
   */
  public void identify(LDUser user) {
    if (this.offline) {
      return;
    }
    boolean processed = eventProcessor.sendEvent(new IdentifyEvent(user));
    if (!processed) {
      logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
    }
  }

  private void sendFlagRequestEvent(String featureKey, LDUser user, boolean value, boolean defaultValue) {
    boolean processed = eventProcessor.sendEvent(new FeatureRequestEvent<>(featureKey, user, value, defaultValue));
    if (!processed) {
      logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
    }
    NewRelicReflector.annotateTransaction(featureKey, String.valueOf(value));
  }

  /**
   * Calculates the value of a feature flag for a given user.
   *
   * @param featureKey the unique featureKey for the feature flag
   * @param user the end user requesting the flag
   * @param defaultValue the default value of the flag
   * @return whether or not the flag should be enabled, or {@code defaultValue} if the flag is disabled in the LaunchDarkly control panel
   * @deprecated As of version 0.7.0, renamed to {@link #toggle(String, LDUser, boolean)}
   */
  public boolean getFlag(String featureKey, LDUser user, boolean defaultValue) {
    return toggle(featureKey, user, defaultValue);
  }

  /**
   * Calculates the value of a feature flag for a given user.
   *
   * @param featureKey the unique featureKey for the feature flag
   * @param user the end user requesting the flag
   * @param defaultValue the default value of the flag
   * @return whether or not the flag should be enabled, or {@code defaultValue} if the flag is disabled in the LaunchDarkly control panel
   */
  public boolean toggle(String featureKey, LDUser user, boolean defaultValue) {
    if (!initialized()) {
      return defaultValue;
    }
    try {
      FeatureRep<Boolean> result = (FeatureRep<Boolean>) config.featureStore.get(featureKey);
      if (result == null) {
        logger.warn("Unknown feature flag " + featureKey + "; returning default value");
        sendFlagRequestEvent(featureKey, user, defaultValue, defaultValue);
        return defaultValue;
      }

      Boolean val = result.evaluate(user);
      if (val == null) {
        sendFlagRequestEvent(featureKey, user, defaultValue, defaultValue);
        return defaultValue;
      } else {
        sendFlagRequestEvent(featureKey, user, val, defaultValue);
        return val;
      }
    } catch (Exception e) {
      logger.error("Encountered exception in LaunchDarkly client", e);
      sendFlagRequestEvent(featureKey, user, defaultValue, defaultValue);
      return defaultValue;
    }
  }



  /**
   * Closes the LaunchDarkly client event processing thread and flushes all pending events. This should only
   * be called on application shutdown.
   *
   * @throws IOException
   */
  @Override
  public void close() throws IOException {
    this.eventProcessor.close();
    if (this.updateProcessor != null) {
      this.updateProcessor.close();
    }
  }

  /**
   * Flushes all pending events
   */
  public void flush() {
    this.eventProcessor.flush();
  }

  /**
   * Puts the LaunchDarkly client in offline mode.
   * In offline mode, all calls to {@link #toggle(String, LDUser, boolean)} will return the default value, and
   * {@link #track(String, LDUser, com.google.gson.JsonElement)} will be a no-op.
   *
   */
  public void setOffline() {
    this.offline = true;
  }

  /**
   * Puts the LaunchDarkly client in online mode.
   *
   */
  public void setOnline() {
    this.offline = false;
  }

  /**
   * @return whether the client is in offline mode
   */
  public boolean isOffline() {
    return this.offline;
  }

  private static String getClientVersion() {
    Class clazz = LDConfig.class;
    String className = clazz.getSimpleName() + ".class";
    String classPath = clazz.getResource(className).toString();
    if (!classPath.startsWith("jar")) {
      // Class not from JAR
      return "Unknown";
    }
    String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) +
        "/META-INF/MANIFEST.MF";
    Manifest manifest = null;
    try {
      manifest = new Manifest(new URL(manifestPath).openStream());
      Attributes attr = manifest.getMainAttributes();
      String value = attr.getValue("Implementation-Version");
      return value;
    } catch (IOException e) {
      logger.warn("Unable to determine LaunchDarkly client library version", e);
      return "Unknown";
    }
  }
}