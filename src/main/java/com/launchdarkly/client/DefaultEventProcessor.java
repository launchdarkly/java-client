package com.launchdarkly.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.launchdarkly.client.EventSummarizer.EventSummary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class DefaultEventProcessor implements EventProcessor {
  private static final Logger logger = LoggerFactory.getLogger(DefaultEventProcessor.class);
  static final SimpleDateFormat HTTP_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
  private static final int CHANNEL_BLOCK_MILLIS = 1000;
  
  private final BlockingQueue<EventProcessorMessage> inputChannel;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean inputCapacityExceeded = new AtomicBoolean(false);
  
  DefaultEventProcessor(String sdkKey, LDConfig config) {
    inputChannel = new ArrayBlockingQueue<>(config.capacity);

    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("LaunchDarkly-EventProcessor-%d")
        .build();
    scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);

    new EventDispatcher(sdkKey, config, inputChannel, threadFactory);

    Runnable flusher = new Runnable() {
      public void run() {
        postMessageAsync(MessageType.FLUSH, null);
      }
    };
    this.scheduler.scheduleAtFixedRate(flusher, config.flushInterval, config.flushInterval, TimeUnit.SECONDS);
    Runnable userKeysFlusher = new Runnable() {
      public void run() {
        postMessageAsync(MessageType.FLUSH_USERS, null);
      }
    };
    this.scheduler.scheduleAtFixedRate(userKeysFlusher, config.userKeysFlushInterval, config.userKeysFlushInterval,
        TimeUnit.SECONDS);
  }
  
  @Override
  public void sendEvent(Event e) {
    if (!closed.get()) {
      postMessageAsync(MessageType.EVENT, e);
    }
  }
  
  @Override
  public void flush() {
    if (!closed.get()) {
      postMessageAsync(MessageType.FLUSH, null);
    }
  }

  @Override
  public void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      scheduler.shutdown();
      postMessageAsync(MessageType.FLUSH, null);
      postMessageAndWait(MessageType.SHUTDOWN, null);
    }
  }
  
  @VisibleForTesting
  void waitUntilInactive() throws IOException {
    // Waits until there are no pending events or flushes
    postMessageAndWait(MessageType.SYNC, null);
  }
  
  private void postMessageAsync(MessageType type, Event event) {
    postToChannel(new EventProcessorMessage(type, event, false));
  }
  
  private void postMessageAndWait(MessageType type, Event event) {
    EventProcessorMessage message = new EventProcessorMessage(type, event, true);
    postToChannel(message);
    message.waitForCompletion();
  }
  
  private void postToChannel(EventProcessorMessage message) {
    while (true) {
      try {
        if (inputChannel.offer(message, CHANNEL_BLOCK_MILLIS, TimeUnit.MILLISECONDS)) {
          inputCapacityExceeded.set(false);
          break;
        } else {
          // This doesn't mean that the output event buffer is full, but rather that the main thread is
          // seriously backed up with not-yet-processed events. We shouldn't see this.
          if (inputCapacityExceeded.compareAndSet(false, true)) {
            logger.warn("Events are being produced faster than they can be processed");
          }
        }
      } catch (InterruptedException ex) {
      }
    }
  }

  private static enum MessageType {
    EVENT,
    FLUSH,
    FLUSH_USERS,
    SYNC,
    SHUTDOWN
  }
  
  private static final class EventProcessorMessage {
    private final MessageType type;
    private final Event event;
    private final Semaphore reply;
    
    private EventProcessorMessage(MessageType type, Event event, boolean sync) {
      this.type = type;
      this.event = event;
      reply = sync ? new Semaphore(0) : null;
    }
    
    void completed() {
      if (reply != null) {
        reply.release();
      }
    }
    
    void waitForCompletion() {
      if (reply == null) {
        return;
      }
      while (true) {
        try {
          reply.acquire();
          return;
        }
        catch (InterruptedException ex) {
        }
      }
    }
    
    @Override
    public String toString() {
      return ((event == null) ? type.toString() : (type + ": " + event.getClass().getSimpleName())) +
          (reply == null ? "" : " (sync)");
    }
  }
  
  /**
   * Takes messages from the input queue, updating the event buffer and summary counters
   * on its own thread.
   */
  private static final class EventDispatcher {
    private static final int MAX_FLUSH_THREADS = 5;
    
    private final LDConfig config;
    private final List<SendEventsTask> flushWorkers;
    private final AtomicInteger activeFlushWorkersCount;
    private final Random random = new Random();
    private final AtomicLong lastKnownPastTime = new AtomicLong(0);
    private final AtomicBoolean disabled = new AtomicBoolean(false);

    private EventDispatcher(String sdkKey, LDConfig config,
                            final BlockingQueue<EventProcessorMessage> inputChannel,
                            ThreadFactory threadFactory) {
      this.config = config;
      this.activeFlushWorkersCount = new AtomicInteger(0);

      // This queue only holds one element; it represents a flush task that has not yet been
      // picked up by any worker, so if we try to push another one and are refused, it means
      // all the workers are busy.
      final BlockingQueue<FlushPayload> payloadQueue = new ArrayBlockingQueue<>(1);
      
      final EventBuffer buffer = new EventBuffer(config.capacity);
      final SimpleLRUCache<String, String> userKeys = new SimpleLRUCache<String, String>(config.userKeysCapacity);
      
      Thread mainThread = threadFactory.newThread(new Runnable() {
        public void run() {
          runMainLoop(inputChannel, buffer, userKeys, payloadQueue);
        }
      });
      mainThread.setDaemon(true);
      mainThread.start();
      
      flushWorkers = new ArrayList<>();
      EventResponseListener listener = new EventResponseListener() {
          public void handleResponse(Response response) {
            EventDispatcher.this.handleResponse(response);
          }
        };
      for (int i = 0; i < MAX_FLUSH_THREADS; i++) {
        SendEventsTask task = new SendEventsTask(sdkKey, config, listener, payloadQueue,
            activeFlushWorkersCount, threadFactory);
        flushWorkers.add(task);
      }
    }
    
    /**
     * This task drains the input queue as quickly as possible. Everything here is done on a single
     * thread so we don't have to synchronize on our internal structures; when it's time to flush,
     * dispatchFlush will fire off another task to do the part that takes longer.
     */
    private void runMainLoop(BlockingQueue<EventProcessorMessage> inputChannel,
        EventBuffer buffer, SimpleLRUCache<String, String> userKeys,
        BlockingQueue<FlushPayload> payloadQueue) {
      while (true) {
        try {
          EventProcessorMessage message = inputChannel.take();
          switch(message.type) {
          case EVENT:
            processEvent(message.event, userKeys, buffer);
            break;
          case FLUSH:
            triggerFlush(buffer, payloadQueue);
            break;
          case FLUSH_USERS:
            userKeys.clear();
            break;
          case SYNC:
            waitUntilAllFlushWorkersInactive();
            message.completed();
            break;
          case SHUTDOWN:
            doShutdown();
            message.completed();
            return;
          }
          message.completed();
        } catch (InterruptedException e) {
        } catch (Exception e) {
          logger.error("Unexpected error in event processor: " + e);
          logger.debug(e.getMessage(), e);
        }
      }
    }
    
    private void doShutdown() {
      waitUntilAllFlushWorkersInactive();
      disabled.set(true); // In case there are any more messages, we want to ignore them
      for (SendEventsTask task: flushWorkers) {
        task.stop();
      }
      // Note that we don't close the HTTP client here, because it's shared by other components
      // via the LDConfig.  The LDClient will dispose of it.
    }

    private void waitUntilAllFlushWorkersInactive() {
      while (true) {
        try {
          synchronized(activeFlushWorkersCount) {
            if (activeFlushWorkersCount.get() == 0) {
              return;
            } else {
              activeFlushWorkersCount.wait();
            }
          }
        } catch (InterruptedException e) {}
      }
    }
    
    private void processEvent(Event e, SimpleLRUCache<String, String> userKeys, EventBuffer buffer) {
      if (disabled.get()) {
        return;
      }
      
      // For each user we haven't seen before, we add an index event - unless this is already
      // an identify event for that user.
      if (!config.inlineUsersInEvents && e.user != null && !noticeUser(e.user, userKeys)) {
        if (!(e instanceof Event.Identify)) {
          Event.Index ie = new Event.Index(e.creationDate, e.user);
          buffer.add(ie);
        }
      }
      
      // Always record the event in the summarizer.
      buffer.addToSummary(e);

      if (shouldTrackFullEvent(e)) {
        // Sampling interval applies only to fully-tracked events.
        if (config.samplingInterval > 0 && random.nextInt(config.samplingInterval) != 0) {
          return;
        }
        // Queue the event as-is; we'll transform it into an output event when we're flushing
        // (to avoid doing that work on our main thread).
        buffer.add(e);
      }
    }
    
    // Add to the set of users we've noticed, and return true if the user was already known to us.
    private boolean noticeUser(LDUser user, SimpleLRUCache<String, String> userKeys) {
      if (user == null || user.getKey() == null) {
        return false;
      }
      String key = user.getKeyAsString();
      return userKeys.put(key, key) != null;
    }
    
    private boolean shouldTrackFullEvent(Event e) {
      if (e instanceof Event.FeatureRequest) {
        Event.FeatureRequest fe = (Event.FeatureRequest)e;
        if (fe.trackEvents) {
          return true;
        }
        if (fe.debugEventsUntilDate != null) {
          // The "last known past time" comes from the last HTTP response we got from the server.
          // In case the client's time is set wrong, at least we know that any expiration date
          // earlier than that point is definitely in the past.  If there's any discrepancy, we
          // want to err on the side of cutting off event debugging sooner.
          long lastPast = lastKnownPastTime.get();
          if (fe.debugEventsUntilDate > lastPast &&
              fe.debugEventsUntilDate > System.currentTimeMillis()) {
            return true;
          }
        }
        return false;
      } else {
        return true;
      }
    }

    private void triggerFlush(EventBuffer buffer, BlockingQueue<FlushPayload> payloadQueue) {
      if (disabled.get() || buffer.isEmpty()) {
        return;
      }
      FlushPayload payload = buffer.getPayload();
      activeFlushWorkersCount.incrementAndGet();
      if (payloadQueue.offer(payload)) {
        // These events now belong to the next available flush worker, so drop them from our state
        buffer.clear();
      } else {
        logger.debug("Skipped flushing because all workers are busy");
        // All the workers are busy so we can't flush now; keep the events in our state
        synchronized(activeFlushWorkersCount) {
          activeFlushWorkersCount.decrementAndGet();
          activeFlushWorkersCount.notify();
        }
      }
    }
    
    private void handleResponse(Response response) {
      String dateStr = response.header("Date");
      if (dateStr != null) {
        try {
          lastKnownPastTime.set(HTTP_DATE_FORMAT.parse(dateStr).getTime());
        } catch (ParseException e) {
        }
      }
      if (!response.isSuccessful()) {
        logger.info("Got unexpected response when posting events: " + response);
        if (response.code() == 401) {
          disabled.set(true);
          logger.error("Received 401 error, no further events will be posted since SDK key is invalid");
        }
      }
    }
  }
  
  private static final class EventBuffer {
    final List<Event> events = new ArrayList<>();
    final EventSummarizer summarizer = new EventSummarizer();
    private final int capacity;
    private boolean capacityExceeded = false;
    
    EventBuffer(int capacity) {
      this.capacity = capacity;
    }
    
    void add(Event e) {
      if (events.size() >= capacity) {
        if (!capacityExceeded) { // don't need AtomicBoolean, this is only checked on one thread
          capacityExceeded = true;
          logger.warn("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
        }
      } else {
        capacityExceeded = false;
        events.add(e);
      }
    }
    
    void addToSummary(Event e) {
      summarizer.summarizeEvent(e);
    }
    
    boolean isEmpty() {
      return events.isEmpty() && summarizer.snapshot().isEmpty();
    }
    
    FlushPayload getPayload() {
      Event[] eventsOut = events.toArray(new Event[events.size()]);
      EventSummarizer.EventSummary summary = summarizer.snapshot();
      return new FlushPayload(eventsOut, summary);
    }
    
    void clear() {
      events.clear();
      summarizer.clear();
    }
  }
  
  private static final class FlushPayload {
    final Event[] events;
    final EventSummary summary;
    
    FlushPayload(Event[] events, EventSummary summary) {
      this.events = events;
      this.summary = summary;
    }
  }
  
  private static interface EventResponseListener {
    void handleResponse(Response response);
  }
  
  private static final class SendEventsTask implements Runnable {
    private final String sdkKey;
    private final LDConfig config;
    private final EventResponseListener responseListener;
    private final BlockingQueue<FlushPayload> payloadQueue;
    private final AtomicInteger activeFlushWorkersCount;
    private final AtomicBoolean stopping;
    private final EventOutput.Formatter formatter;
    private final Thread thread;
    
    SendEventsTask(String sdkKey, LDConfig config, EventResponseListener responseListener,
                   BlockingQueue<FlushPayload> payloadQueue, AtomicInteger activeFlushWorkersCount,
                   ThreadFactory threadFactory) {
      this.sdkKey = sdkKey;
      this.config = config;
      this.formatter = new EventOutput.Formatter(config.inlineUsersInEvents);
      this.responseListener = responseListener;
      this.payloadQueue = payloadQueue;
      this.activeFlushWorkersCount = activeFlushWorkersCount;
      this.stopping = new AtomicBoolean(false);
      thread = threadFactory.newThread(this);
      thread.setDaemon(true);
      thread.start();
    }
    
    public void run() {
      while (!stopping.get()) {
        FlushPayload payload = null;
        try {
          payload = payloadQueue.take();
        } catch (InterruptedException e) {
          continue;
        }
        try {
          List<EventOutput> eventsOut = formatter.makeOutputEvents(payload.events, payload.summary);
          if (!eventsOut.isEmpty()) {
            postEvents(eventsOut);
          }
        } catch (Exception e) {
          logger.error("Unexpected error in event processor: " + e);
          logger.debug(e.getMessage(), e);
        }
        synchronized (activeFlushWorkersCount) {
          activeFlushWorkersCount.decrementAndGet();
          activeFlushWorkersCount.notifyAll();
        }
      }
    }
    
    void stop() {
      stopping.set(true);
      thread.interrupt();
    }
    
    private void postEvents(List<EventOutput> eventsOut) {
      String json = config.gson.toJson(eventsOut);
      logger.debug("Posting {} event(s) to {} with payload: {}",
          eventsOut.size(), config.eventsURI, json);

      Request request = config.getRequestBuilder(sdkKey)
          .url(config.eventsURI.toString() + "/bulk")
          .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json))
          .addHeader("Content-Type", "application/json")
          .build();

      long startTime = System.currentTimeMillis();
      try (Response response = config.httpClient.newCall(request).execute()) {
        long endTime = System.currentTimeMillis();
        logger.debug("Event delivery took {} ms, response status {}", endTime - startTime, response.code());
        responseListener.handleResponse(response);
      } catch (IOException e) {
        logger.info("Unhandled exception in LaunchDarkly client when posting events to URL: " + request.url(), e);
      }
    }
  }
}
